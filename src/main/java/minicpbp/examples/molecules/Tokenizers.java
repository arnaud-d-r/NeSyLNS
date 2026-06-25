package minicpbp.examples.molecules;

import java.util.Vector;

public class Tokenizers {
    public static Vector<String> tokenizeV1(String molecule) {
        char[] moleculeChars = molecule.toCharArray();
        Vector<String> tokens = new Vector<>();
        for (int i = 0; i < moleculeChars.length; i++) {
            if (i != moleculeChars.length - 1 && moleculeChars[i] == 'C' && moleculeChars[i+1] == 'l') {
                tokens.add("Cl");
                i += 1;
                continue;
            } else if (i != moleculeChars.length - 1 && moleculeChars[i] == 'B' && moleculeChars[i+1] == 'r') {
                tokens.add("Br");
                i += 1;
                continue;
            }
            tokens.add(String.format("%c", moleculeChars[i]));
        }
        return tokens;
    }

    public static Vector<String> tokenizeV2(String molecule) {
        char[] moleculeChars = molecule.toCharArray();
        Vector<String> tokens = new Vector<>();
        for (int i = 0; i < moleculeChars.length; i++) {
            if (moleculeChars[i] == '%') {
                tokens.add(String.format("%%c%c", moleculeChars[i+1], moleculeChars[i+2]));
                i += 2; // Skips the next two chars
                continue;
            } else if (i != moleculeChars.length - 1 && moleculeChars[i] == 'C' && moleculeChars[i+1] == 'l') {
                tokens.add("Cl");
                i += 1;
                continue;
            } else if (i != moleculeChars.length - 1 && moleculeChars[i] == 'B' && moleculeChars[i+1] == 'r') {
                tokens.add("Br");
                i += 1;
                continue;
            }
            tokens.add(String.format("%c", moleculeChars[i]));
        }
        return tokens;
    }

    public static Vector<String> tokenizeV7(String molecule) {
        char[] moleculeChars = molecule.toCharArray();
        Vector<String> tokens = new Vector<>();
        for (int i = 0; i < moleculeChars.length; i++) {
            if (moleculeChars[i] == '%') {
                tokens.add(String.format("%%c%c", moleculeChars[i+1], moleculeChars[i+2]));
                i += 2; // Skips the next two chars
                continue;
            } else if (i != moleculeChars.length - 1 && moleculeChars[i] == 'C' && moleculeChars[i+1] == 'l') {
                tokens.add("Cl");
                i += 1;
                continue;
            } else if (i != moleculeChars.length - 1 && moleculeChars[i] == 'B' && moleculeChars[i+1] == 'r') {
                tokens.add("Br");
                i += 1;
                continue;
            } else if (i != moleculeChars.length - 1 && moleculeChars[i] == 'H' && moleculeChars[i+1] == '3') {
                tokens.add("H3");
                i += 1;
                continue;
            }
            tokens.add(String.format("%c", moleculeChars[i]));
        }
        return tokens;
    }
}
