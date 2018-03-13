package testablejava;

public class CallContext<CALLED> {
    public final String callingClass;
    public final String calledClass;
    public final Object callingClassInstance;
    public final CALLED calledClassInstance;
    public final Object[] enclosingInstances;

    /**
     * class Calling {
     *     ... Called.call()
     * }
     * @param callingClass name of the class where the calling code resides
     * @param calledClass name of the class where called code resides
     * @param callingClassInstance instance of the calling class, or null if called from static context
     * @param calledClassInstance instance of the called class, or null if called method is static
     * @param enclosingInstances for nested classes: hidden call arguments
     */
    public CallContext(
            String callingClass,
            String calledClass,
            Object callingClassInstance,
            CALLED calledClassInstance,
            Object... enclosingInstances){

        this.callingClass = callingClass;
        this.calledClass = calledClass;
        this.callingClassInstance = callingClassInstance;
        this.calledClassInstance = calledClassInstance;
        this.enclosingInstances = enclosingInstances;
    }
    /**
     * class Calling {
     *     ... Called.call()
     * }
     * @param callingClass name of the class where the calling code resides
     * @param calledClass name of the class where called code resides
     * @param callingClassInstance instance of the calling class, or null if called from static context
     * @param calledClassInstance instance of the called class, or null if called method is static
     */
    public CallContext(
            String callingClass,
            String calledClass,
            Object callingClassInstance,
            CALLED calledClassInstance
     ){
        this(callingClass, calledClass, callingClassInstance, calledClassInstance, new Object[0]);
    }
}
