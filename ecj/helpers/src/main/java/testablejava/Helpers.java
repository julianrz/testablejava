package testablejava;

public class Helpers {
    public static void uncheckedThrow(Exception ex) {
//        throw ex; //bytecode rewrite
        throw new RuntimeException("instrumentation failure");
    }
}

class HelpersTemplate {
    public static void uncheckedThrow(Exception ex) throws Exception {
        throw ex;
    }
}
