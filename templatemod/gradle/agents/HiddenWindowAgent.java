package psymc.agent;

import java.io.File;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.ProtectionDomain;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

// A smoke client with a real OpenGL context must not show its window.
// This agent changes the LWJGL GLFW class:
// - Each glfwCreateWindow first sets the window hint GLFW_VISIBLE to false.
// - glfwShowWindow does nothing.
// The agent argument is the path of the ASM jar.
// ASM loads in a separate class loader. The game classpath then has no second ASM.
public final class HiddenWindowAgent {
  private static final int GLFW_VISIBLE = 0x00020004;

  private HiddenWindowAgent() {}

  public static void premain(String args, Instrumentation instrumentation) throws Exception {
    URL agentJar = HiddenWindowAgent.class.getProtectionDomain().getCodeSource().getLocation();
    URL asmJar = new File(args).toURI().toURL();
    ClassLoader isolated =
        new URLClassLoader(new URL[] {agentJar, asmJar}, ClassLoader.getPlatformClassLoader());
    Class<?> transformer =
        Class.forName("psymc.agent.HiddenWindowAgent$Transformer", true, isolated);
    instrumentation.addTransformer(
        (ClassFileTransformer) transformer.getDeclaredConstructor().newInstance());
    System.err.println("HiddenWindowAgent: GLFW windows stay hidden");
  }

  public static final class Transformer implements ClassFileTransformer {
    @Override
    public byte[] transform(
        ClassLoader loader,
        String className,
        Class<?> classBeingRedefined,
        ProtectionDomain protectionDomain,
        byte[] classfileBuffer)
        throws IllegalClassFormatException {
      if (!"org/lwjgl/glfw/GLFW".equals(className)) {
        return null;
      }
      try {
        ClassReader reader = new ClassReader(classfileBuffer);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new GlfwVisitor(writer), ClassReader.EXPAND_FRAMES);
        System.err.println("HiddenWindowAgent: changed " + className);
        return writer.toByteArray();
      } catch (Throwable error) {
        System.err.println("HiddenWindowAgent: " + error);
        return null;
      }
    }
  }

  private static final class GlfwVisitor extends ClassVisitor {
    GlfwVisitor(ClassVisitor next) {
      super(Opcodes.ASM9, next);
    }

    @Override
    public MethodVisitor visitMethod(
        int access, String name, String descriptor, String signature, String[] exceptions) {
      MethodVisitor next = super.visitMethod(access, name, descriptor, signature, exceptions);
      if ((access & Opcodes.ACC_NATIVE) != 0) {
        return next;
      }
      if (name.equals("glfwCreateWindow")) {
        return new MethodVisitor(Opcodes.ASM9, next) {
          @Override
          public void visitCode() {
            super.visitCode();
            super.visitLdcInsn(GLFW_VISIBLE);
            super.visitInsn(Opcodes.ICONST_0);
            super.visitMethodInsn(
                Opcodes.INVOKESTATIC, "org/lwjgl/glfw/GLFW", "glfwWindowHint", "(II)V", false);
          }
        };
      }
      if (name.equals("glfwShowWindow") && descriptor.equals("(J)V")) {
        return new MethodVisitor(Opcodes.ASM9, next) {
          @Override
          public void visitCode() {
            super.visitCode();
            super.visitInsn(Opcodes.RETURN);
            // The old body is now dead code. The verifier needs a frame after RETURN.
            super.visitFrame(Opcodes.F_NEW, 1, new Object[] {Opcodes.LONG}, 0, new Object[0]);
          }
        };
      }
      return next;
    }
  }
}
