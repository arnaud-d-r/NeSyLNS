package minicpbp.examples.config;

import java.net.http.HttpClient;
import java.util.ArrayList;

public interface MoleculeBuilder {
    String buildMolecule(ArrayList<ScoredMolecule> bases, HttpClient client, int port, double mask_percent);
}
