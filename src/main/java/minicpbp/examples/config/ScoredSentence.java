package minicpbp.examples.config;

public  class ScoredSentence {
    private String sentence;
    private double perplexity;
    private double weight;

    public ScoredSentence(String sentence, double perplexity) {
        this.sentence = sentence;
        this.perplexity = perplexity;
        this.weight = 0.0;
    }
    
    public String getSentence() { return sentence; }
    public double getPerplexity() { return perplexity; }
    public double getWeight() { return weight; }
    public void setWeight(double weight) { this.weight = weight; }
    
    @Override
    public String toString() {
        return String.format("%s (Perplexity: %.2f, Weight: %.4f)", 
                        sentence, perplexity, weight);
    }
}
