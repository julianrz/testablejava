package org.testability;

import java.util.*;
import java.util.function.Function;
import java.util.stream.IntStream;

import static java.util.stream.Collectors.*;

public class Util {
    /**
     *
     * @param descriptors
     * @param fromCol
     * @param maxCols
     * @param inRows   @return true if any col has unique row values
     */
    static boolean isUniqueFromColInRows(List<List<String>> descriptors, int fromCol, int maxCols, List<Integer> inRows) {

        return inRows.stream().
            map(iRow -> descriptors.get(iRow).stream().skip(fromCol).collect(toList())).
            map(list-> Arrays.toString(list.toArray())).
            collect(toSet()).
            size() == inRows.size();
    }

    static boolean hasDuplicates(List<String> values) {
        return new HashSet<>(values).size() != values.size();
    }

    static List<Integer> occurrenceIndices(List<String> values, String value) {
        return IntStream.range(0,values.size()).
                filter(i-> values.get(i).equals(value)).
                mapToObj(lst->lst).
                collect(toList());
    }

    /**
     *
     * @param values
     * @return map value -> list of positions where duplicate occurs
     */
    static Map<String, List<Integer>> duplicatePositions(List<String> values) {
        Set<String> uniqueValues = values.stream().collect(toSet());

        return uniqueValues.stream().
                collect(toMap(
                        Function.identity(),
                        value -> occurrenceIndices(values, value)
                )).entrySet().stream().
                filter(e -> e.getValue().size() > 1).
                collect(toMap(
                        e -> e.getKey(),
                        e -> e.getValue()
                ));
    }

    static List<List<String>> cloneAndEqualizeMatrix(List<List<String>> descriptors, int rowSize, String filler) {
        List<List<String>> ret = new ArrayList<>();
        for (int i=0;i<descriptors.size();i++) {
            List<String> row = descriptors.get(i);
            ArrayList<String> clonedRow = new ArrayList<>();
            clonedRow.addAll(row);

            while (clonedRow.size() < rowSize)
                clonedRow.add(filler);

            ret.add(clonedRow);
        }
        return ret;
    }


    static String lastChunk(String s, String separator) {
        int iSep = s.lastIndexOf(separator);
        if (iSep < 0)
            return s;
        return s.substring(iSep + 1);
    }

    /**
     *
     *  modify in place descriptors to make them unique - work inside column fromCol and rows inRows
     *  descriptor is made unique by replacing it with appropriate long version (from the same position in longDescriptors)
     *
     * @param descriptors assumes all rows have length maxCols
     * @param longDescriptors -"-
     * @param maxCols
     * @param fromCol starting position to consider
     * @param inRows set of row indexes to work on (window)
     */
    static boolean uniqueMatrix(
            List<List<String>> descriptors,
            List<List<String>> longDescriptors,
            int maxCols,
            int fromCol,
            List<Integer> inRows
            ) {

        if (isUniqueFromColInRows(descriptors, fromCol, maxCols, inRows))
            return true;

        List<String> colShort = inRows.stream().
                map(irow -> descriptors.get(irow).get(fromCol)).
                collect(toList());

        //find rows that contain same values, group by value
        Map<String, List<Integer>> duplicateGroups = duplicatePositions(colShort);

        boolean alreadyUnique = duplicateGroups.isEmpty();

        if (alreadyUnique)
            return true;

        //attempt to fix each group by using long descriptor, if fails uniqueness, next row

        List<String> longDescriptionsCol = inRows.stream().
                map(irow -> longDescriptors.get(irow).get(fromCol)).
                collect(toList());

        return duplicateGroups.values().stream().
            allMatch(groupRows -> {

                //groupRows contains indexes into colShort, original

                Set<String> uniqueElementsAfterSubst = groupRows.stream().
                        map(relIndex -> longDescriptionsCol.get(relIndex)).
                        collect(toSet());

                boolean isMoreUniqueAfterSubst = uniqueElementsAfterSubst.size() > 1; //group had all same elements

                if (isMoreUniqueAfterSubst) {

                    groupRows.stream().forEach(indexIntoColShort -> {
                        int iAbsRow = inRows.get(indexIntoColShort);
                        String longDescr = longDescriptors.get(iAbsRow).get(fromCol);
                        List<String> descriptorsRow = descriptors.get(iAbsRow);
                        descriptorsRow.set(fromCol, longDescr);
                    });

                    //deep-probe the result. There may be a combination of this subst and further value that makes it uniqeu
                    if (isUniqueFromColInRows(descriptors, fromCol, maxCols, inRows))
                        return true;
                    //re-classify. Each new group has same value in current column, and needs to be further resolved from next column
                }

                int newICol = isMoreUniqueAfterSubst? fromCol : fromCol + 1; //re-classifies when substitution successful (stays on same col) or moves on to next col if no difference through substitution

                boolean ret = true;

                if (newICol < maxCols) {

                    List<Integer> newInRows = groupRows.stream().
                            map(indexIntoColShort -> inRows.get(indexIntoColShort)).
                            collect(toList());

                    ret = uniqueMatrix(
                            descriptors,
                            longDescriptors,
                            maxCols,
                            newICol,
                            newInRows);

                } else {
                    //could not make unique
                    ret = false;
                }

                return ret;

            });
    }

    /**
     *
     * @param shortDescriptors matrix with short descriptor values
     * @param longDescriptors matrix with long descriptor values
     * @return matrix with same dimensions, each row being unique and each position having eithe
     *  - shortDescriptor or
     *  - longDescriptor
     *  The result should be unique rows, e.g:
     *  When a column is all-unique, subsequent columns are not needed
     */
    static Optional<String [][]> uniqueMatrix(String [][] shortDescriptors, String [][] longDescriptors) throws Exception {
        int maxRowLen = 0;
        for (int iRow=0;iRow<shortDescriptors.length;iRow++)
            maxRowLen = Math.max(maxRowLen, shortDescriptors[iRow].length);

        List<List<String>> descriptors =
                cloneAndEqualizeMatrix(
                        matrixToListOfLists(shortDescriptors), maxRowLen, "");

        boolean ret = uniqueMatrix(
                descriptors,
                cloneAndEqualizeMatrix(matrixToListOfLists(longDescriptors), maxRowLen, ""),
                maxRowLen,
                0,
                IntStream.range(0, shortDescriptors.length).mapToObj(i -> i).collect(toList())
        );

        return ret? Optional.of(listOfListsToMatrix(descriptors)) : Optional.empty();

    }

    static List<List<String>> matrixToListOfLists(String[][] matrix) {
        return Arrays.stream(matrix).map(row ->
                Arrays.stream(row).collect(toList())).
                collect(toList());
    }

    static String[][] listOfListsToMatrix(List<List<String>> matrix) {
        return matrix.stream().map(row ->
                row.toArray(new String[row.size()])).
                collect(toList()).toArray(new String[matrix.size()][]);
    }
}
