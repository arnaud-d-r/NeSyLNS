package minicpbp.examples.config;


public class ScoredMolecule {
    String molecule;
    double score;
    double weight=0.0;
    
    public ScoredMolecule(String molecule, double score) {
        this.molecule = molecule;
        this.score = score;
    }
    
    public double getWeight() {
        return weight;
    }

    public void setWeight(double weight) {
        this.weight = weight;
    }
    
    @Override
    public boolean equals(Object obj) {
        if (obj instanceof ScoredMolecule) {
            return this.molecule.equals(((ScoredMolecule) obj).molecule);
        }
        return false;
    }
    
    @Override
    public int hashCode() {
        return molecule.hashCode();
    }

    public String getMolecule() {
        return molecule;
    }

    public double getScore() {
        return score;
    }

    
    @Override
    public String toString() {
        return String.format("%s (Score: %.2f, Weight: %.4f)", 
                        molecule, score, weight);
    }
}
