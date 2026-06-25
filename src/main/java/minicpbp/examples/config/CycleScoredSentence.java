package minicpbp.examples.config;

public class CycleScoredSentence {
    String sentence;
    double score;
    long time;
    int cycle;

    public CycleScoredSentence(String sentence, double score, long time, int cycle) {
        this.sentence = sentence;
        this.score = score;
        this.time = time;
        this.cycle = cycle;
    }

    public String getSentence() { return sentence; }
    public double getScore() { return score; }
    public long getTime() { return time; }
    public int getCycle() { return cycle; }
    @Override public String toString() {
        return "CycleScoredSentence{sentence='" + sentence + "', score=" + score +
            ", time=" + time + ", cycle=" + cycle + "}";
    }
}
    
