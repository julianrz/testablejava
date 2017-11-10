package org.testability;

import com.google.common.collect.ImmutableMap;
import org.junit.Test;

import static java.util.Arrays.asList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class UtilTest {
    @Test
    public void isUniqueFromColInRows() throws Exception {
        assertTrue(Util.isUniqueFromColInRows(
                asList(
                        asList("a", "a"),
                        asList("b", "b"),
                        asList("a", "b"),
                        asList("b", "a")
                ),
                0,
                2,
                asList(0,1,2,3)
        ));
        assertFalse(Util.isUniqueFromColInRows(
                asList(
                        asList("a", "a"),
                        asList("b", "b"),
                        asList("a", "b"),
                        asList("b", "b")
                ),
                0,
                2,
                asList(0,1,2,3)
        ));
    }

    @Test
    public void duplicatePositions() throws Exception {
        assertEquals(ImmutableMap.of(), Util.duplicatePositions(asList("1","2","3")));
        assertEquals(ImmutableMap.of("2",asList(1,2)), Util.duplicatePositions(asList("1","2","2","3")));
        assertEquals(
                ImmutableMap.of(
                        "2", asList(1, 2),
                        "4", asList(4, 5),
                        "5", asList(6, 7, 8)
                ),
                Util.duplicatePositions(asList("1", "2", "2", "3", "4", "4", "5", "5", "5")));
        assertEquals(ImmutableMap.of(), Util.duplicatePositions(asList()));
    }
    @Test
    public void occurenceIndices() throws Exception {
        assertEquals(asList(1), Util.occurrenceIndices(asList("1","2","3"),"2"));
        assertEquals(asList(1, 2, 6), Util.occurrenceIndices(asList("1","2","2","3","4","5","2","1"),"2"));
        assertEquals(asList(), Util.occurrenceIndices(asList("1","2","3"),"4"));
        assertEquals(asList(), Util.occurrenceIndices(asList(),"4"));
    }

    @Test
    public void hasDuplicates() throws Exception {
        assertFalse(Util.hasDuplicates(asList("1","2","3")));
        assertTrue(Util.hasDuplicates(asList("1","2","2","3")));
        assertTrue(Util.hasDuplicates(asList("1","1")));
        assertFalse(Util.hasDuplicates(asList()));
    }

}