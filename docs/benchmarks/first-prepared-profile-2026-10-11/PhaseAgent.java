/*
 * The Compukters Developers
 *
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.lang.instrument.*;
import java.security.ProtectionDomain;
import java.util.*;
import jdk.jfr.*;
import org.jetbrains.org.objectweb.asm.*;

public class PhaseAgent {
    @Name("compukters.MethodPhase")
    @jdk.jfr.Label("Compiler method boundary")
    public static class MethodPhase extends Event { public String method; }
    private static final ThreadLocal<Deque<MethodPhase>> active = ThreadLocal.withInitial(ArrayDeque::new);
    public static void enter(String method) {
        MethodPhase event = new MethodPhase(); event.method = method; event.begin(); active.get().push(event);
    }
    public static void leave(String method) {
        Deque<MethodPhase> stack = active.get();
        if (stack.isEmpty() || !stack.peek().method.equals(method)) throw new IllegalStateException("phase nesting mismatch: " + method);
        MethodPhase event = stack.pop(); event.end(); event.commit();
    }
    public static void premain(String args, Instrumentation instrumentation) {
        FlightRecorder.register(MethodPhase.class);
        Map<String, Set<String>> targets = Map.of(
            "ru/lazyhat/compukters/platform/k2/build/CompuktersFirBuildEnvironment", Set.of("compileGuest", "createGuestRequestEnvironment"),
            "ru/lazyhat/compukters/compiler/k2/engine/CompuktersFir2IrPipeline", Set.of("convert"),
            "ru/lazyhat/compukters/compiler/k2/engine/MinimalScriptLowering", Set.of("lower"),
            "ru/lazyhat/compukters/compiler/artifact/link/LibraryModuleLinker", Set.of("linkAndWrite")
        );
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader loader, String className, Class<?> cls, ProtectionDomain domain, byte[] bytes) {
                Set<String> methods = targets.get(className); if (methods == null) return null;
                ClassReader reader = new ClassReader(bytes); ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
                reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                        MethodVisitor downstream = super.visitMethod(access, name, desc, signature, exceptions);
                        if (!methods.contains(name)) return downstream;
                        String label = className + "." + name;
                        return new MethodVisitor(Opcodes.ASM9, downstream) {
                            void hook(String action) { super.visitLdcInsn(label); super.visitMethodInsn(Opcodes.INVOKESTATIC, "PhaseAgent", action, "(Ljava/lang/String;)V", false); }
                            @Override public void visitCode() { super.visitCode(); hook("enter"); }
                            @Override public void visitInsn(int opcode) { if ((opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) || opcode == Opcodes.ATHROW) hook("leave"); super.visitInsn(opcode); }
                        };
                    }
                }, 0);
                return writer.toByteArray();
            }
        });
    }
}
