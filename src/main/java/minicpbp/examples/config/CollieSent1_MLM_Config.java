package minicpbp.examples.config;

import com.ibm.icu.impl.Pair;

import minicpbp.cp.Factory;
import minicpbp.engine.core.*;

public class CollieSent1_MLM_Config implements ConstraintBuilder {

    final static private int MAX_WORDS = 20;
    final static private int MIN_WORDS = 10;
    MNREAD_MLM_Config.RefType ref;

    public CollieSent1_MLM_Config(MNREAD_MLM_Config.RefType ref) {
        this.ref = ref;
    }

    @Override
    public String getInstruction() {
        return "Please generate a sentence with exactly 82 characters. Include whitespace into your character count. Talk about fruits and the beach.";
    }

    

    @Override
    public void build(SolverContext ctx) {

        for(int i=0; i<ctx.words.size(); i++) {
            if(Character.isUpperCase(ctx.words.get(i).charAt(0))) {
                ctx.word_index[0].remove(i);
            }
        }

        final int NUMBER_CHAR = 82-1;//Verify if you need to count the spaces at the beginning of line + No period at the end
        // create num_char array using the solver from the provided context and the word_index length
        IntVar[] num_char = Factory.makeIntVarArray(ctx.cp, ctx.word_index.length,
                java.util.Arrays.stream(ctx.charNum).min().getAsInt(),
                java.util.Arrays.stream(ctx.charNum).max().getAsInt());

        for (int j = 0; j < ctx.word_index.length; j++) {
            ctx.word_index[j].setName("word_index[" + j + "]");
            ctx.cp.post(Factory.element(ctx.charNum, ctx.word_index[j], num_char[j]));
        }

        // use the sum of the provided lengthTokens as the target total number of chars (adjust if you have a dedicated IntVar)
        ctx.cp.post(Factory.sum(num_char, NUMBER_CHAR));


    }



    @Override
    public Pair<Integer, Integer> getWordCountRange() {
        return Pair.of(MIN_WORDS, MAX_WORDS);
    }



    @Override
    public String fileRef() {
        switch (ref) {
            default:
                return "src/main/java/minicpbp/examples/config/files_references/result_CollieSent1Config_v3_1761184263207_sentences.txt";
        }
    }



    @Override
    public Boolean isValid() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'isValid'");
    }
    
}
