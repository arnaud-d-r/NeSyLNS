package minicpbp.examples.config;

import com.ibm.icu.impl.Pair;

import minicpbp.cp.Factory;
import minicpbp.engine.core.IntVar;

public class MNREAD_MLM_Config implements ConstraintBuilder {
    final static private int NUMBER_CHAR = 60; // Verify if you need to count the spaces at the beginning of line + No period at the end
    final static private int LINE_SIZE = 15896;
    final static private int SPACE_SIZE =512;
    final static private int MIN_SPACE_SIZE =410;
    final static private int MAX_SPACE_SIZE =640;
    final static private int MAX_NUMBER_SPACE = 4;
    final static private int MIN_NUMBER_WORD = 9;
    final static private int MAX_NUMBER_WORD = 15;
    RefType ref;
    IntVar[] lines;
    IntVar[] sizes;

    @Override
    public String getInstruction() {
        return "Please generate a sentence with exactly 60 characters. Include whitespace into your character count.";
    }

    public enum RefType {
        BONLARRON,
        AUTHORS,
        DEFAULT,
        CP,
        CP_LLM,
        INVALID_EASY,
    }

    public MNREAD_MLM_Config(RefType ref) {
        this.ref = ref;
    }

    @Override
    public void build(SolverContext ctx) {
       

        for(int i=0; i<ctx.words.size(); i++) {
            if(Character.isUpperCase(ctx.words.get(i).charAt(0))) {
                ctx.word_index[0].remove(i);
            }
        }

        // sizes, word_index, has_space, num_char
        minicpbp.engine.core.IntVar[] sizes = minicpbp.cp.Factory.makeIntVarArray(
                ctx.cp,
                ctx.word_index.length,
                java.util.Arrays.stream(ctx.lengthTokens).min().getAsInt(),
                java.util.Arrays.stream(ctx.lengthTokens).max().getAsInt()
        );
        minicpbp.engine.core.IntVar[] word_index = ctx.word_index;
        minicpbp.engine.core.IntVar[] num_char = minicpbp.cp.Factory.makeIntVarArray(
                ctx.cp,
                sizes.length,
                java.util.Arrays.stream(ctx.charNum).min().getAsInt(),
                java.util.Arrays.stream(ctx.charNum).max().getAsInt()
        );

        for (int i = 0; i < sizes.length; i++) {
            sizes[i].setName("size[" + i + "]");
            word_index[i].setName("word_index[" + i + "]");
            ctx.cp.post(minicpbp.cp.Factory.element(ctx.lengthTokens, word_index[i], sizes[i]));
            ctx.cp.post(minicpbp.cp.Factory.element(ctx.charNum, word_index[i], num_char[i]));
        }


        // total characters constraint
        ctx.cp.post(minicpbp.cp.Factory.sum(num_char, NUMBER_CHAR));

        // lines and packing
        int nbLines = 3;
        //minicpbp.engine.core.IntVar[] line = minicpbp.cp.Factory.makeIntVarArray(ctx.cp, sizes.length, 0, nbLines - 1);
        minicpbp.engine.core.IntVar[] line = ctx.line;
        for (int i = 0; i < line.length; i++)
            line[i].setName("line[" + i + "]");

        minicpbp.engine.core.IntVar[] lineSize = minicpbp.cp.Factory.makeIntVarArray(
                        ctx.cp,
                        nbLines,
                        LINE_SIZE + SPACE_SIZE - MAX_NUMBER_SPACE * (MAX_SPACE_SIZE - SPACE_SIZE),
                        LINE_SIZE + SPACE_SIZE + MAX_NUMBER_SPACE * (SPACE_SIZE - MIN_SPACE_SIZE)
                );
        for (int i = 0; i < lineSize.length; i++)
            lineSize[i].setName("lineSize[" + i + "]");

        // tighten: count number of words per line and link per-line total sizes
        minicpbp.engine.core.IntVar[] numWords = minicpbp.cp.Factory.makeIntVarArray(ctx.cp, nbLines, 0, sizes.length);
        for (int j = 0; j < numWords.length; j++)
            numWords[j].setName("numWords[" + j + "]");

        line[0].assign(0);
        line[line.length - 1].assign(nbLines - 1);

        for (int i = 0; i < line.length - 1; i++) {
            ctx.cp.post(minicpbp.cp.Factory.lessOrEqual(line[i], line[i + 1]));
            ctx.cp.post(minicpbp.cp.Factory.lessOrEqual(line[i + 1], minicpbp.cp.Factory.plus(line[i], 1)));
            ctx.cp.post(minicpbp.cp.Factory.notEqual(word_index[i], word_index[i + 1]));
        }

        
        ctx.cp.post(minicpbp.cp.Factory.binPacking(line, sizes, lineSize));

        // post counts and per-line algebraic bounds so solver enforces the same math as isValid()
        for (int j = 0; j < nbLines; j++) {
            minicpbp.engine.core.IntVar[] inLine = minicpbp.cp.Factory.makeIntVarArray(ctx.cp, sizes.length, 0, 1);
            for (int i = 0; i < sizes.length; i++) {
            inLine[i] = minicpbp.cp.Factory.isEqual(line[i], j);
            }
            // numWords[j] = sum_i isEqual(line[i], j)
            ctx.cp.post(minicpbp.cp.Factory.sum(inLine, numWords[j]));

                // minW = lineSize[j] - SPACE_SIZE + (numWords[j]-1)*(MIN_SPACE_SIZE - SPACE_SIZE)
                minicpbp.engine.core.IntVar exprA = minicpbp.cp.Factory.minus(lineSize[j], SPACE_SIZE);
                minicpbp.engine.core.IntVar exprB = minicpbp.cp.Factory.mul(minicpbp.cp.Factory.minus(numWords[j], 1), (MIN_SPACE_SIZE - SPACE_SIZE));
                minicpbp.engine.core.IntVar minExpr = minicpbp.cp.Factory.sum(exprA, exprB);
                // maxW = lineSize[j] - SPACE_SIZE + (numWords[j]-1)*(MAX_SPACE_SIZE - SPACE_SIZE)
                minicpbp.engine.core.IntVar exprC = minicpbp.cp.Factory.mul(minicpbp.cp.Factory.minus(numWords[j], 1), (MAX_SPACE_SIZE - SPACE_SIZE));
                minicpbp.engine.core.IntVar maxExpr = minicpbp.cp.Factory.sum(exprA, exprC);

            // enforce minW <= LINE_SIZE <= maxW
            ctx.cp.post(minicpbp.cp.Factory.lessOrEqual(minExpr, minicpbp.cp.Factory.makeIntVar(ctx.cp, LINE_SIZE, LINE_SIZE)));
            ctx.cp.post(minicpbp.cp.Factory.lessOrEqual(minicpbp.cp.Factory.makeIntVar(ctx.cp, LINE_SIZE, LINE_SIZE), maxExpr));
        }

        this.lines = line;
        this.sizes = sizes;


    }

    @Override
    public Pair<Integer, Integer> getWordCountRange() {
        return Pair.of(MIN_NUMBER_WORD, MAX_NUMBER_WORD);
    }

    public String fileRef() {
        switch (ref) {
            case BONLARRON:
                return "src/main/java/minicpbp/examples/config/files_references/IJCAI2023_EN_BENCH_SORTED.txt";
            case AUTHORS:
                return "src/main/java/minicpbp/examples/config/files_references/MNREAD_authors_9_millions_samples.txt";
        }
        return null;
    }

    @Override
    public Boolean isValid() {
        IntVar[] line = this.lines;
        IntVar[] sizes = this.sizes;
        int totalSize1 = 0, totalSize2 = 0, totalSize3 = 0;
        int numWords1 = 0, numWords2 = 0, numWords3 = 0;
        for (int i = 0; i < sizes.length; i++) {
            if(!line[i].isBound() || !sizes[i].isBound()) System.out.println("Line or size variable is not bound");
            if (line[i].valueWithMaxMarginal() == 0) {
                totalSize1 += sizes[i].valueWithMaxMarginal();
                numWords1++;
            } else if (line[i].valueWithMaxMarginal() == 1) {
                totalSize2 += sizes[i].valueWithMaxMarginal();
                numWords2++;
            } else if (line[i].valueWithMaxMarginal() == 2) {
                totalSize3 += sizes[i].valueWithMaxMarginal();
                numWords3++;
            }
        }
        if(LINE_SIZE > totalSize1 - SPACE_SIZE + (numWords1-1) * (MAX_SPACE_SIZE - SPACE_SIZE)  ||
           LINE_SIZE < totalSize1 - SPACE_SIZE + (numWords1-1) * (MIN_SPACE_SIZE - SPACE_SIZE)) {
            System.out.println("Invalid line 1: totalSize=" + totalSize1 + ", numWords=" + numWords1);
            return false;
        }
        if(LINE_SIZE > totalSize2 - SPACE_SIZE + (numWords2-1) * (MAX_SPACE_SIZE - SPACE_SIZE)  ||
           LINE_SIZE < totalSize2 - SPACE_SIZE + (numWords2-1) * (MIN_SPACE_SIZE - SPACE_SIZE)) {
            System.out.println("Invalid line 2: totalSize=" + totalSize2 + ", numWords=" + numWords2);
            return false;
        }
        if(LINE_SIZE > totalSize3 - SPACE_SIZE + (numWords3-1) * (MAX_SPACE_SIZE - SPACE_SIZE)  ||
           LINE_SIZE < totalSize3 - SPACE_SIZE + (numWords3-1) * (MIN_SPACE_SIZE - SPACE_SIZE)) {
            System.out.println("Invalid line 3: totalSize=" + totalSize3 + ", numWords=" + numWords3);
            return false;
        }
        return true;
    }
    
}
