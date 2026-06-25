package minicpbp.examples.config;

public class CycleScoredMolecule {
        String molecule;
        double score;
        long time;
        int cycle;
        
        public CycleScoredMolecule(String molecule, double score, long time, int cycle) {
            this.molecule = molecule;
            this.score = score;
            this.time = time;
            this.cycle = cycle;
        }

    public String getMolecule() { return molecule; }
    public double getScore() { return score; }
    public long getTime() { return time; }
    public int getCycle() { return cycle; }
    @Override public String toString() {
        return "CycleScoredMolecule{molecule='" + molecule + "', score=" + score +
            ", time=" + time + ", cycle=" + cycle + "}";
    }
}
    
