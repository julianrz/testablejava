import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class XTest {
//    @Test
//    public void replaceNewOperatorInClass() throws Exception {
//
//        X x = new X();
//        x.$$java$lang$String$new = (arg) -> {dontredirect: return new String("redirected");};
//        String ret = x.fnUsingNew();
//        assertEquals(ret,"redirected");
//    }
    //TODO compilation problem
    @Test
    public void replaceExternalStaticCallInClass() throws Exception {

        X x = new X();
        x.$$Integer$parseInt = (arg) -> {dontredirect: return 2;};
        int ret = x.fnUsingExternalCall("1");
        assertEquals(ret,2);
    }

    @Test
    public void instantiationListener() throws Exception {

        //here instantiation is performed in library code, so by the time we get the instance,
        //it may have been utilized (without our instrumentation). To ensure we instrument as soon
        //as the instance is created, we use the postCreate listener on the class X; the listener receives
        //the new instance, and we instrument it right away. The instrumentation replaces any string creation
        //to return hardcoded string "redirected"

        //set up listener
        X.$$postCreate = (X x) -> {
            //instrument X instance
            x.$$String$new = (arg) -> {dontredirect: return new String("redirected");};
        };
        dontredirect:
        {
            //imitate library creation of instance. Here we do get the result, but in a more complex case,
            //the library may be using the result and not returning it. But here because of prior listener setup
            //all instances will be instrumented
            X x = (X)this.getClass().getClassLoader().loadClass("X").newInstance();
            String ret = x.fnUsingNew("original"); //normally returns its argument
            assertEquals(ret, "redirected"); //but here returns the mock value
        }
    }

    public static void main(String[] args) throws Exception {
//        new XTest().replaceNewOperatorInClass();
//        new XTest().replaceExternalStaticCallInClass();
    }

}