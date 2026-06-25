package minicpbp.examples.molecules;

import static minicpbp.cp.Factory.among;
import static minicpbp.cp.Factory.element;
import static minicpbp.cp.Factory.equal;
import static minicpbp.cp.Factory.grammar;
import static minicpbp.cp.Factory.isEqual;
import static minicpbp.cp.Factory.makeIntVar;
import static minicpbp.cp.Factory.makeIntVarArray;
import static minicpbp.cp.Factory.regular;
import static minicpbp.cp.Factory.sum;
import static minicpbp.cp.Factory.table;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Vector;

import minicpbp.engine.constraints.ShortTableCT;
import minicpbp.engine.core.IntVar;
import minicpbp.engine.core.Solver;
import minicpbp.util.CFG;

public class GenConstraints {
    public static void grammarConstraint(Solver cp, IntVar[] w, CFG g) throws FileNotFoundException, IOException {
        cp.post(grammar(w,g));
    }

    public static int[] getCycleTokens(CFG g, int start, int end) {
        int nbCount = end - start + 1;
        int[] cycleTokens = new int[nbCount];
        for (int i = start; i < start + nbCount; i++) {
            String key = i < 10 ? Integer.toString(i) : '%' + Integer.toString(i);
            cycleTokens[i-start] = g.tokenEncoder.get(key);
        }
        return cycleTokens;
    }

    public static void cycleCountingConstraint(Solver cp, IntVar[] w, CFG g, int start, int end) {
        int[] cycleTokens = getCycleTokens(g, start, end);

        int n = g.terminalCount();
        int[][] A = new int[cycleTokens.length][n];
        for (int i = 0; i < cycleTokens.length; i++) {
            // Default loop
            for (int j = 0; j < n; j++) {
                A[i][j] = i;
            }
            // Disallow all numbers above the one associated to the current state
            for (int j = i + 1; j < cycleTokens.length; j++) {
                A[i][cycleTokens[j]] = -1;
            }
            // Create the transitions to go up a state, except for the last state
            if (i != A.length - 1) {
                A[i][cycleTokens[i]] = i + 1;
            }
        }
        cp.post(regular(w, A));
    }

    public static void cycleParityConstraint(Solver cp, IntVar[] w, CFG g, int start, int end) {
        int[] cycleTokens = getCycleTokens(g, start, end);
        for (int i = 0; i < cycleTokens.length; i++) {
            IntVar numberOccurrences = makeIntVar(cp, 0, 2);
            numberOccurrences.remove(1);
            cp.post(among(w, cycleTokens[i], numberOccurrences));
        }
    }

    public static void moleculeWeightConstraint(Solver cp, IntVar[] w, IntVar[] tokenWeights, IntVar weightTarget, CFG g) {
        int[] tokenToWeight = new int[g.terminalCount()];
        for (int i = 0; i < g.terminalCount(); i++) {
            String token = g.tokenDecoder.get(i);
            if (g.tokenWeight.containsKey(token)) {
                tokenToWeight[i] = g.tokenWeight.get(token);
            } else {
                tokenToWeight[i] = 0;
            }
        }
        for (int i = 0; i < w.length; i++) {
            cp.post(element(tokenToWeight, w[i], tokenWeights[i]));
        }
        cp.post(sum(tokenWeights, weightTarget));
    }

    public static void limitCycleConstraint(Solver cp, IntVar[] w, CFG g, int nCycles) {
        // A negative value disables the constraint
        if (nCycles == 0) { // Ensures no cycles
            cp.post(among(w, g.tokenEncoder.get("1"), makeIntVar(cp, 0,0)));
        } else if (nCycles > 0) {
            cp.post(among(w, g.tokenEncoder.get(Integer.toString(nCycles)), makeIntVar(cp, 2,2)));
            cp.post(among(w, g.tokenEncoder.get(Integer.toString(nCycles + 1)), makeIntVar(cp, 0,0)));
        }
    }

    public static void limitBranchConstraint(Solver cp, IntVar[] w, CFG g, int nBranches) {
        // A negative value disables the constraint
        if (nBranches >= 0) {
            cp.post(among(w, g.tokenEncoder.get("("), makeIntVar(cp, nBranches, nBranches)));
        }
    }
    
    public static void limitAcceptors(Solver cp, IntVar[] w, CFG g, IntVar target) {
        cp.post(
            among(
                w,
                new int[]{
                    g.tokenEncoder.get("N"),
                    g.tokenEncoder.get("T"),
                    g.tokenEncoder.get("O"),
                    g.tokenEncoder.get("X"),
                    g.tokenEncoder.get("S"),
                    g.tokenEncoder.get("R"),
                    g.tokenEncoder.get("n"),
                    g.tokenEncoder.get("o"),
                    g.tokenEncoder.get("s"),
                },
                target
            )
        );
    }

    public static void limitDonors(Solver cp, IntVar[] w, CFG g, IntVar target) {
        cp.post(
            among(
                w,
                new int[]{
                    g.tokenEncoder.get("T"),
                    g.tokenEncoder.get("X"),
                    g.tokenEncoder.get("R"),
                },
                target
            )
        );
    }

    public static void avoidBranchOnEnd(Solver cp, IntVar[] w, CFG g) {
        int n = g.terminalCount();
        int[][] A = new int[2][n];

        // Setup base state transitions
        for (int i = 0; i < n; i++) {
            A[0][i] = 1; // All tokens lead to the accepted state
            A[1][i] = 1; // Once accepted, it stays accepted
        }

        A[0][g.tokenEncoder.get("_")] = 0; // Padding keeps us in the start state
        A[0][g.tokenEncoder.get(")")] = -1; // A closing branch being the last token is forbidden

        IntVar[] wInverse = makeIntVarArray(cp, w.length, 0, g.terminalCount()-1);;
        for (int i = 0; i < w.length; i++) {
            cp.post(isEqual(wInverse[i], w[w.length - 1 - i]));
        }
        
        cp.post(regular(wInverse, A));
    }

    private static boolean isCycleToken(String token) {
        return token.equals("1") ||
            token.equals("2") ||
            token.equals("3") ||
            token.equals("4") ||
            token.equals("5") ||
            token.equals("6") ||
            token.equals("7") ||
            token.equals("8");
    }

    private static boolean isBondToken(String token) {
        return token.equals("=") || token.equals("#");
    }

    private static boolean isPosNegToken(String token) {
        return token.equals("-") || token.equals("+");
    }

    private static boolean isAtomToken(String token) {
        return token.equals("C") ||
            token.equals("c") ||
            token.equals("O") ||
            token.equals("o") ||
            token.equals("N") ||
            token.equals("n") ||
            token.equals("S") ||
            token.equals("s") ||
            token.equals("F") ||
            token.equals("Cl") ||
            token.equals("I") ||
            token.equals("Br");
    }

    private static boolean isValidNextToken(int left, int right, CFG g) {
        String rToken = g.tokenDecoder.get(right);
        String lToken = g.tokenDecoder.get(left);
        if (lToken.equals("-")) {
            return !(rToken.equals("+") || isBondToken(rToken));
        } else if (lToken.equals("+")) {
            return !(rToken.equals("-") || isBondToken(rToken) || isAtomToken(rToken));
        } else if (lToken.equals("[")) {
            return !(rToken.equals("]") || isPosNegToken(rToken) || isBondToken(rToken) || isCycleToken(rToken));
        } else if (isBondToken(lToken)) {
            return !(isBondToken(rToken) || rToken.equals("-"));
        }
        return true;
    }

    private static int[][] generateEmptyLingoTable(CFG g) {
        Vector<int[]> elementVector = new Vector<>();
        for (int a = 0; a < g.terminalCount(); a++) {
            for (int b = 0; b < g.terminalCount(); b++) {
                if (!isValidNextToken(a, b, g)) continue;
                for (int c = 0; c < g.terminalCount(); c++) {
                    if (!isValidNextToken(b, c, g)) continue;
                    for (int d = 0; d < g.terminalCount(); d++) {
                        if (!isValidNextToken(c, d, g)) continue;
                        elementVector.add(new int[]{a,b,c,d,0});
                    }
                }
            }
        }
        System.out.println(Math.pow(g.terminalCount(),4) - elementVector.size());
        int[][] elementTable = new int[elementVector.size()][5];
        elementTable = elementVector.toArray(elementTable);
        return elementTable;
    }

    public static IntVar shortLingo(
        Solver cp,
        IntVar[] w,
        CFG g,
        String filePath,
        int minValue,
        int maxValue
    ) throws FileNotFoundException, IOException {
        final int STAR = -1;

        //#region File reading
        HashMap<Integer,HashMap<Integer,HashMap<Integer,HashMap<Integer,Integer>>>> elementMap = new HashMap<>();
        BufferedReader reader = new BufferedReader(new FileReader(filePath));            
        while (reader.ready()) {
            // Read, convert to right tokens, associate to weight * 100 rounded
            String[] line = reader.readLine().split(" ");
            String[] tokens = line[0].split(",");
            int weight = Math.round(Float.parseFloat(line[1]) * 100);

            if (!g.tokenEncoder.keySet().contains(tokens[0])) {
                System.out.println(tokens[0]);
            } else if (!g.tokenEncoder.keySet().contains(tokens[1])) {
                System.out.println(tokens[1]);
            } else if (!g.tokenEncoder.keySet().contains(tokens[2])) {
                System.out.println(tokens[2]);
            } else if (!g.tokenEncoder.keySet().contains(tokens[3])) {
                System.out.println(tokens[3]);
            }

            int a = g.tokenEncoder.get(tokens[0]);
            if (!elementMap.containsKey(a)) {
                elementMap.put(a, new HashMap<>());
            }

            int b = g.tokenEncoder.get(tokens[1]);
            if (!elementMap.get(a).containsKey(b)) {
                elementMap.get(a).put(b, new HashMap<>());
            }

            int c = g.tokenEncoder.get(tokens[2]);
            if (!elementMap.get(a).get(b).containsKey(c)) {
                elementMap.get(a).get(b).put(c, new HashMap<>());
            }

            int d = g.tokenEncoder.get(tokens[3]);
            if (!elementMap.get(a).get(b).get(c).containsKey(d)) {
                elementMap.get(a).get(b).get(c).put(d, weight);
            }
        }
        reader.close();
        //#endregion

        //#region Generating Lingo table
        Vector<int[]> elementVector = new Vector<>();
        for (int a = 0; a < g.terminalCount(); a++) {
            if (!elementMap.keySet().contains(a)) {
                elementVector.add(new int[]{a,STAR,STAR,STAR,0});
                continue;
            }
            for (int b = 0; b < g.terminalCount(); b++) {
                if (!isValidNextToken(a, b, g)) continue;
                if (!elementMap.get(a).keySet().contains(b)) {
                    elementVector.add(new int[]{a,b,STAR,STAR,0});
                    continue;
                }
                for (int c = 0; c < g.terminalCount(); c++) {
                    if (!isValidNextToken(b, c, g)) continue;
                    if (!elementMap.get(a).get(b).keySet().contains(c)) {
                        elementVector.add(new int[]{a,b,c,STAR,0});
                        continue;
                    }
                    for (int d = 0; d < g.terminalCount(); d++) {
                        if (!isValidNextToken(c, d, g)) continue;
                        if (!elementMap.get(a).get(b).get(c).keySet().contains(d)) {
                            elementVector.add(new int[]{a,b,c,d,0});
                            continue;
                        }
                        elementVector.add(new int[]{a,b,c,d,elementMap.get(a).get(b).get(c).get(d)});
                    }
                }
            }
        }

        //#endregion

        int[][] elementTable = new int[elementVector.size()][5];
        elementTable = elementVector.toArray(elementTable);

        FileWriter file = new FileWriter("fullLingos.txt");
        for (int[] iter : elementVector) {
            for (int i = 0; i < iter.length; i++) {
                if (i == iter.length - 1) {
                    file.append(String.valueOf(iter[i]) + "\n");
                    continue;
                }
                if (iter[i] == -1) {
                    file.append("*,");
                    continue;
                }
                file.append(g.tokenDecoder.get(iter[i]) + ",");
            }
        }
        file.close();
        System.out.println("Wrote to file");

        // System.out.println("Original size: " + Math.pow(g.terminalCount(), 4));
        // System.out.println("Current size: " + elementTable.length);
        // System.out.println(Math.pow(g.terminalCount(), 4) - elementTable.length);

        // Adding the constraints to the model
        // System.out.println("Starting");            
        IntVar[] logPValues = makeIntVarArray(cp, w.length - 3, -200, 200);
        for (int i = 0; i < logPValues.length; i++) {
            cp.post(new ShortTableCT(
                new IntVar[] {w[i],w[i+1],w[i+2],w[i+3],logPValues[i]},
                elementTable,
                STAR
            ));
        }
        // CCCC(C(=C(C=CC(C1OCNC1(S))(S))NC=N))C
        // System.out.println("Survived");
        IntVar logPEstimate = makeIntVar(cp, minValue, maxValue);
        cp.post(sum(logPValues, logPEstimate));
        return logPEstimate;
    }

    public static IntVar longLingo(
        Solver cp,
        IntVar[] w,
        CFG g,
        String filePath,
        int minValue,
        int maxValue
    ) throws FileNotFoundException, IOException {
        System.out.println("long");
        try {
            BufferedReader reader = new BufferedReader(new FileReader(filePath));            
            HashMap<Integer,HashMap<Integer,HashMap<Integer,HashMap<Integer,Integer>>>> elementMap = new HashMap<>();
            while (reader.ready()) {
                // Read, convert to right tokens, associate to weight * 100 rounded
                String[] line = reader.readLine().split(" ");
                String[] tokens = line[0].split(",");
                int weight = Math.round(Float.parseFloat(line[1]) * 100);

                int a = g.tokenEncoder.get(tokens[0]);
                if (!elementMap.containsKey(a)) {
                    elementMap.put(a, new HashMap<>());
                }

                int b = g.tokenEncoder.get(tokens[1]);
                if (!elementMap.get(a).containsKey(b)) {
                    elementMap.get(a).put(b, new HashMap<>());
                }

                int c = g.tokenEncoder.get(tokens[2]);
                if (!elementMap.get(a).get(b).containsKey(c)) {
                    elementMap.get(a).get(b).put(c, new HashMap<>());
                }

                int d = g.tokenEncoder.get(tokens[3]);
                if (!elementMap.get(a).get(b).get(c).containsKey(d)) {
                    elementMap.get(a).get(b).get(c).put(d, weight);
                }
            }
            reader.close();
            
            int[][] elementTable = generateEmptyLingoTable(g);

            for (int i = 0; i < elementTable.length; i++) {
                int[] iter = elementTable[i];
                if (
                    !elementMap.containsKey(iter[0]) ||
                    !elementMap.get(iter[0]).containsKey(iter[1]) ||
                    !elementMap.get(iter[0]).get(iter[1]).containsKey(iter[2]) ||
                    !elementMap.get(iter[0]).get(iter[1]).get(iter[2]).containsKey(iter[3])
                ) {
                    continue;
                }
                elementTable[i][4] = elementMap.get(iter[0]).get(iter[1]).get(iter[2]).get(iter[3]);
            }
            System.out.println("Starting");            
            IntVar[] logPValues = makeIntVarArray(cp, w.length - 3, -800, 800);
            for (int i = 0; i < logPValues.length; i++) {
                cp.post(table(
                    new IntVar[] {w[i],w[i+1],w[i+2],w[i+3],logPValues[i]},
                    elementTable
                ));
            }
            System.out.println("Survived");
            IntVar logPEstimate = makeIntVar(cp, minValue, maxValue);
            cp.post(sum(logPValues, logPEstimate));
            return logPEstimate;
        } catch (FileNotFoundException e) {
            throw e;
        } catch (IOException e) {
            throw e;
        }
    }

    public static IntVar lingoConstraint(
        Solver cp,
        IntVar[] w,
        CFG g,
        String filePath,
        int minValue,
        int maxValue
    ) throws FileNotFoundException, IOException {
        return shortLingo(cp, w, g, filePath, minValue, maxValue);
    }

    public static void setMolecule(Solver cp, IntVar[] w, CFG g, String molecule) {
        String[] molVector = new String[0];
        molVector = Tokenizers.tokenizeV7(molecule).toArray(molVector);
        String[] tokens = new String[w.length];
        for (int i = 0; i < tokens.length; i++) {
            if (i < molVector.length) {
                tokens[i] = molVector[i];
            } else {
                tokens[i] = "_";
            }
        }
        for (int i = 0; i < tokens.length; i++) {
            int value = g.tokenEncoder.get(tokens[i]);
            cp.post(equal(w[i], makeIntVar(cp,value,value)));
        }
    }
}
