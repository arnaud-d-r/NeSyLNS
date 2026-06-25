package minicpbp.examples.config;

import com.ibm.icu.impl.Pair;

import minicpbp.cp.Factory;
import minicpbp.engine.core.*;

public class CollieSent4_MLM_Config implements ConstraintBuilder {

    final static private int MAX_WORDS = 20;
    final static private int MIN_WORDS = 5;

    @Override
    public String getInstruction() {
        return "Generate a sentence but be sure to include the words “soft”, “beach” and “math”.";
    }

    

    @Override
    public void build(SolverContext ctx) {

        for(int i=0; i<ctx.words.size(); i++) {
            if(Character.isUpperCase(ctx.words.get(i).charAt(0))) {
                ctx.word_index[0].remove(i);
            }
        }

        int idxBeach = -1, idxSoft = -1, idxWater = -1;
        int count = 0;
        while ((idxBeach == -1 || idxSoft == -1 || idxWater == -1) && count < ctx.words.size()) {
            String word = ctx.words.get(count);
            switch (word) {
                case " beach":
                    if (idxBeach != -1) throw new RuntimeException("The word 'beach' appears multiple times in the corpus.");
                    idxBeach = count;
                    break;
                case " soft":
                    if (idxSoft != -1) throw new RuntimeException("The word 'soft' appears multiple times in the corpus.");
                    idxSoft = count;
                    break;
                case " water":
                    if (idxWater != -1) throw new RuntimeException("The word 'water' appears multiple times in the corpus.");
                    idxWater = count;
                    break;
                default:
                    break;
            }
            count++;
        }
        if(idxBeach == -1 || idxSoft == -1 || idxWater == -1) {
            throw new RuntimeException("Could not find all required words in the corpus.");
        }

        ctx.cp.post(Factory.atleast(ctx.word_index, idxSoft, 1));
        ctx.cp.post(Factory.atleast(ctx.word_index, idxBeach, 1));
        ctx.cp.post(Factory.atleast(ctx.word_index, idxWater, 1));
        ctx.cp.post(Factory.atleast(ctx.word_index, new int[]{idxSoft,idxBeach,idxWater}, 3));


    }



    @Override
    public Pair<Integer, Integer> getWordCountRange() {
        return Pair.of(MIN_WORDS, MAX_WORDS);
    }



    @Override
    public String fileRef() {
        return "src/main/java/minicpbp/examples/config/files_references/result_CollieSent4Config_v3_1761225545879_sentences.txt";
    }



    @Override
    public Boolean isValid() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'isValid'");
    }
    
}
