/*
 * Copyright 2018 Sam Sun <github-contact@samczsun.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.javadeobfuscator.deobfuscator.transformers.stringer.invokedynamic;

import com.javadeobfuscator.deobfuscator.config.TransformerConfig;
import com.javadeobfuscator.deobfuscator.exceptions.WrongTransformerException;
import com.javadeobfuscator.deobfuscator.transformers.Transformer;
import com.javadeobfuscator.deobfuscator.utils.InstructionModifier;
import com.javadeobfuscator.deobfuscator.utils.TransformerHelper;
import com.javadeobfuscator.javavm.VirtualMachine;
import com.javadeobfuscator.javavm.exceptions.AbortException;
import com.javadeobfuscator.javavm.exceptions.VMException;
import com.javadeobfuscator.javavm.mirrors.JavaClass;
import com.javadeobfuscator.javavm.nativeimpls.java_lang_Class;
import com.javadeobfuscator.javavm.nativeimpls.java_lang_invoke_MethodType;
import com.javadeobfuscator.javavm.values.JavaWrapper;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * This mode of Stringer's invokedynamic obfuscation generates a bootstrap method for each invokedynamic with the signature
 * {@code (Ljava/lang/Object;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/Object;}
 */
public class Invokedynamic2Transformer extends Transformer<TransformerConfig> implements Opcodes {
    public static final String BSM_DESC = "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/Object;";

    @Override
    public boolean transform() throws WrongTransformerException {
        VirtualMachine vm = TransformerHelper.newVirtualMachine(this);

        for (ClassNode classNode : classes.values())
            JavaClass.forName(vm, classNode.name).setInitializationState(JavaClass.InitializationState.INITIALIZED, null);

        for (ClassNode classNode : classes.values())
            for (MethodNode methodNode : new ArrayList<>(classNode.methods)) {
                InstructionModifier modifier = new InstructionModifier();

                int decryptorMethodCount = 0;

                for (AbstractInsnNode insn : TransformerHelper.instructionIterator(methodNode)) {
                    if (!(insn instanceof InvokeDynamicInsnNode)) continue;

                    InvokeDynamicInsnNode invokeDynamicInsnNode = (InvokeDynamicInsnNode) insn;
                    if (!invokeDynamicInsnNode.bsm.getOwner().equals(classNode.name)) continue;
                    if (!invokeDynamicInsnNode.bsm.getDesc().equals(BSM_DESC)) continue;

                    MethodNode decryptorMethod = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "Decrypt" + decryptorMethodCount++, "()Ljava/lang/", null, null);

                    Type[] argTypes = Type.getArgumentTypes(invokeDynamicInsnNode.desc);
                    for (Type type : argTypes)
                        decryptorMethod.instructions.add(TransformerHelper.zero(type));

                    decryptorMethod.instructions.add(invokeDynamicInsnNode.clone(null));
                    decryptorMethod.instructions.add(new InsnNode(RETURN));

                    List<JavaWrapper> data = new ArrayList<>();

                    vm.beforeCallHooks.add(info -> {
                        if (info.getClassNode().name.equals("java/lang/invoke/MethodHandles$Lookup")
                                && info.getMethodNode().desc.equals("(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;"))
                            // Make sure it's the BSM calling the find method and not the JVM
                            if (vm.getStacktrace().get(0).getClassNode() == classNode) {
                                data.addAll(info.getParams());
                                throw AbortException.INSTANCE;
                            }
                    });

                    classNode.methods.add(decryptorMethod);
                    try {
                        vm.execute(classNode, decryptorMethod);
                    } catch (AbortException ignored) {
                    } catch (VMException e) {
                        logger.info("Exception while decrypting invokedynamic in {}", classNode.name);
                        logger.info(vm.exceptionToString(e));
                        continue;
                    } catch (Throwable e) {
                        logger.info("Severe exception while decrypting invokedynamic in {}", classNode.name, e);
                        continue;
                    } finally {
                        classNode.methods.remove(decryptorMethod);
                        vm.beforeCallHooks.clear();
                    }

                    if (data.size() != 3) {
                        oops("got bad data for {} {}{}: {}", classNode.name, methodNode.name, methodNode.desc, data);
                        continue;
                    }

                    JavaClass owner = java_lang_Class.getJavaClass(data.get(0));
                    String name = vm.convertJavaObjectToString(data.get(1));
                    String desc = java_lang_invoke_MethodType.asSignature(data.get(2), false);
                    MethodNode indyMethod = owner.findMethodNode(name, desc, true);

                    if (indyMethod == null) {
                        oops("couldn't find method {} {}{}", classNode.name, name, desc);
                        continue;
                    }

                    int opcode;
                    if (Modifier.isStatic(indyMethod.access))
                        opcode = INVOKESTATIC;
                    else {
                        if (Modifier.isInterface(owner.getClassNode().access))
                            opcode = INVOKEINTERFACE;
                        else
                            opcode = INVOKEVIRTUAL;
                    }

                    modifier.replace(invokeDynamicInsnNode, new MethodInsnNode(opcode, owner.getClassNode().name, indyMethod.name, indyMethod.desc, opcode == INVOKEINTERFACE));

                    logger.info("Decrypted {} {}{}", owner.getClassNode().name, name, desc);
                }

                modifier.apply(methodNode);
            }

        vm.shutdown();
        return false;
    }
}
