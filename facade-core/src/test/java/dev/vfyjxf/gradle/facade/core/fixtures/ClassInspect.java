package dev.vfyjxf.gradle.facade.core.fixtures;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.RecordComponentVisitor;
import org.objectweb.asm.util.CheckClassAdapter;

import java.util.ArrayList;
import java.util.List;

public final class ClassInspect {

    private ClassInspect() {}

    public static final class Info {

        public String name;
        public String signature;
        public String superName;
        public String[] interfaces = new String[0];
        public int recordComponents;
        public int methods;
        public int fields;
        public final List<String> permittedSubclasses = new ArrayList<>();
    }

    public static Info info(byte[] classBytes) {
        Info info = new Info();
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(
                    int version, int access, String name, String signature, String superName, String[] interfaces) {
                info.name = name;
                info.signature = signature;
                info.superName = superName;
                info.interfaces = interfaces == null ? new String[0] : interfaces;
            }

            @Override
            public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                info.fields++;
                return null;
            }

            @Override
            public MethodVisitor visitMethod(
                    int access, String name, String descriptor, String signature, String[] exceptions) {
                info.methods++;
                return null;
            }

            @Override
            public RecordComponentVisitor visitRecordComponent(String name, String descriptor, String signature) {
                info.recordComponents++;
                return null;
            }

            @Override
            public void visitPermittedSubclass(String permittedSubclass) {
                info.permittedSubclasses.add(permittedSubclass);
            }
        }, ClassReader.SKIP_CODE);
        return info;
    }

    public static void verify(byte[] classBytes) {
        new ClassReader(classBytes).accept(new CheckClassAdapter(null), 0);
    }
}
