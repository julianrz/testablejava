import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class XTest {
    @Test
    public void fn() throws Exception {


        X x = new X();
        x.$$java$lang$String$new = (arg) -> {dontredirect: return new String("redirected");};
        String ret = x.fn();
        assertEquals(ret,"redirected");
    }

    public static void main(String[] args) throws Exception {
        new XTest().fn();
    }

}