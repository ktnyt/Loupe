package jp.nano.rawgallery;
import java.util.*;

public final class TagConfidenceTest {
  static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
  static RelatedPhotos.Vector v(float... values) { return RelatedPhotos.Vector.from(values); }
  public static void main(String[] args) {
    Map<String, RelatedPhotos.Vector> vectors = Map.of("a",v(1,0),"b",v(0,1),"c",v(.8f,.2f),"d",v(.2f,.8f));
    Map<String,TagJudgments.Value> labels = new HashMap<>();
    labels.put("a",TagJudgments.Value.ASSIGNED);
    check(TagConfidence.fit(vectors,labels,()->false)==null,"positive-only requires feedback");
    labels.put("b",TagJudgments.Value.EXCLUDED);
    TagConfidence.Model model = TagConfidence.fit(vectors,labels,()->false);
    check(model.score(vectors.get("c"))>.7 && model.score(vectors.get("d"))<.3,"learns a boundary");
    check(model.positives==1&&model.negatives==1,"latest human labels only");
    labels.put("d",TagJudgments.Value.ASSIGNED);
    TagConfidence.Model corrected=TagConfidence.fit(vectors,labels,()->false);
    check(corrected.score(vectors.get("d"))>model.score(vectors.get("d")),"correction changes scores");
    labels.put("d",TagJudgments.Value.UNDECIDED);
    check(Arrays.equals(model.encode(),TagConfidence.fit(vectors,labels,()->false).encode()),"undo removes the example");
    check(Arrays.equals(model.encode(),TagConfidence.Model.decode(model.encode()).encode()),"model roundtrip");
    boolean cancelled=false;try{TagConfidence.fit(vectors,labels,()->true);}catch(java.util.concurrent.CancellationException e){cancelled=true;}
    check(cancelled,"cancellation checked during fitting");
    for(double invalid:new double[]{Double.NaN,Double.POSITIVE_INFINITY,-.01,1.01}){
      boolean rejected=false;try{TagConfidence.requireScore(invalid);}catch(IllegalArgumentException e){rejected=true;}
      check(rejected,"invalid score rejected");
    }
    boolean corrupt=false;try{TagConfidence.Model.decode(new byte[28]);}catch(IllegalArgumentException e){corrupt=true;}check(corrupt,"corrupt model rejected");
    List<TagJudgments.Value> unlabeled=List.of(TagJudgments.Value.UNDECIDED,TagJudgments.Value.UNDECIDED,TagJudgments.Value.UNDECIDED,TagJudgments.Value.EXCLUDED);
    Map<Integer,Double> scores=Map.of(0,.59,1,.98,2,.1,3,.6);
    check(TagConfidence.next(unlabeled,scores,Set.of(),.6,0)==0,"boundary query");
    check(TagConfidence.next(unlabeled,scores,Set.of(),.6,3)==1,"high score audit");
    check(TagConfidence.next(unlabeled,scores,Set.of(0,1,2),.6,0)==-1,"pass not repeated and excluded not relabeled");
    check(TagConfidence.next(unlabeled,Map.of(),Set.of(),.6,0)>=0,"manual review without features");
    for(double threshold:new double[]{0,.2,.6,.9,1}) {
      long previous=scores.values().stream().filter(x->x>=Math.max(0,threshold-.01)).count();
      long current=scores.values().stream().filter(x->x>=threshold).count();check(current<=previous,"threshold monotonic");
    }
    System.out.println("TagConfidenceTest passed: boundary learning, corrections, insufficient examples, cancellation, model validation, query selection");
  }
}
