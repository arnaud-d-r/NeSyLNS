package minicpbp.examples.config;
import java.util.List;
import com.ibm.icu.impl.Pair;
import java.net.http.HttpClient;
import java.util.ArrayList;

public interface SentenceBuilder {
    String buildSentence(ArrayList<ScoredSentence> bases, HttpClient client, int port, double mask_percent, ArrayList<Integer> bannedIndices);
}
