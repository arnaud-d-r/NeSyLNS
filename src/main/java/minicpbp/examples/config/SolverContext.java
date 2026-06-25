package minicpbp.examples.config;

import minicpbp.engine.core.Solver;

import java.util.ArrayList;

import minicpbp.engine.core.IntVar;

public class SolverContext {
    public final Solver cp;
    public final int corpusDomains_size;
    public final int end_sentence;
    public final int pad_token;
    public final int[] charNum;
    public final int[] lengthTokens;
    public final IntVar[] word_index;
    public final ArrayList<String> words;
    public final IntVar[] line;

    public SolverContext(Solver cp, int corpusDomains_size, int end_sentence, int pad_token, int[] charNum , int[] lengthTokens, IntVar[] word_index, ArrayList<String> words, IntVar[] line) {
        this.cp = cp;
        this.corpusDomains_size = corpusDomains_size;
        this.end_sentence = end_sentence;
        this.pad_token = pad_token;
        this.charNum = charNum;
        this.lengthTokens = lengthTokens;
        this.word_index = word_index;
        this.words = words;
        this.line = line;
    }
}