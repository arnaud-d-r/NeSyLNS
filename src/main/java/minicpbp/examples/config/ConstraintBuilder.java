package minicpbp.examples.config;

import java.util.ArrayList;

import com.ibm.icu.impl.Pair;

import minicpbp.engine.core.IntVar;

public interface ConstraintBuilder {
    void build(SolverContext ctx);
    String getInstruction();
    Pair<Integer, Integer> getWordCountRange();
    String fileRef();
    Boolean isValid();

    default ArrayList<Integer> getBannedIndices(IntVar[] wordVars){
        return null;
    }
}



