package minicpbp.examples.config;

import static minicpbp.cp.Factory.equal;

import java.util.ArrayList;
import java.util.Arrays;

import com.ibm.icu.impl.Pair;

import minicpbp.cp.Factory;
import minicpbp.engine.core.*;
import minicpbp.examples.config.MNREAD_MLM_Config.RefType;

public class CollieSent2_MLM_Config implements ConstraintBuilder {

    final static private int MAX_WORDS = 10;
    final static private int MIN_WORDS = 10;
    RefType ref;

    @Override
    public String getInstruction() {
        return "Generate a sentence with 10 words, where word 3 is “soft” and word 7 is “beach” and word 10 is “water”.";
    }

    public CollieSent2_MLM_Config(RefType ref) {
        this.ref = ref;
    }

    @Override
    public void build(SolverContext ctx) {

        for(int i=0; i<ctx.words.size(); i++) {
            if(Character.isUpperCase(ctx.words.get(i).charAt(0))) {
                ctx.word_index[0].remove(i);
            }
        }

        boolean isBeach = false, isSoft = false, isMath = false;
        int count = 0;
        while ((!isBeach || !isSoft || !isMath) && count < ctx.words.size()) {
            String word = ctx.words.get(count);
            switch (word) {
                case " beach":
                    if (isBeach) throw new RuntimeException("The word 'beach' appears multiple times in the corpus.");
                    isBeach = true;
                    ctx.word_index[6].assign(count);
                    break;
                case " soft":
                    if (isSoft) throw new RuntimeException("The word 'soft' appears multiple times in the corpus.");
                    isSoft = true;
                    ctx.word_index[2].assign(count);
                    break;
                case " math":
                    if (isMath) throw new RuntimeException("The word 'math' appears multiple times in the corpus.");
                    isMath = true;
                    ctx.word_index[9].assign(count);
                    break;
                default:
                    break;
            }
            count++;
        }
        if(!isBeach || !isSoft || !isMath) {
            throw new RuntimeException("Could not find all required words in the corpus.");
        }

    }



    @Override
    public Pair<Integer, Integer> getWordCountRange() {
        return Pair.of(MIN_WORDS, MAX_WORDS);
    }



    @Override
    public String fileRef() {
        switch (ref) {
            case CP:
                return "src/main/java/minicpbp/examples/config/files_references/valid_sentences_result_CollieSent2_NLP_v0_1765116033079.txt";
            case CP_LLM:
                return "src/main/java/minicpbp/examples/config/files_references/result_CollieSent2Config_v3_1761221684360_sentences.txt";
            default:
                break;
        }
        return null;
    }



    @Override
    public Boolean isValid() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'isValid'");
    }
    @Override
    public ArrayList<Integer> getBannedIndices(IntVar[] wordVars) {
        return new ArrayList<>(Arrays.asList(2, 6, 9));
    }
    
    
}
