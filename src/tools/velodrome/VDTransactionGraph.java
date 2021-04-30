package tools.velodrome;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.List;
import java.util.ListIterator;
import java.util.Collections;
import java.util.Queue;
import java.util.LinkedList;


public class VDTransactionGraph {

  private HashMap<VDTransactionNode, HashSet<VDTransactionNode> > graph;
  
  public VDTransactionGraph() {
    graph = new HashMap<>();
  }

  /**
   * Add an edge to the transaction graph.
   * Also taking care of null boundary cases.
   * @param src Source Node
   * @param dest Destination Node
   */
  public synchronized void addEdge(
    VDTransactionNode src,
    VDTransactionNode dest,
    Set<String> methodsViolatingAtomicity
  ) {
    if(src == dest || src == null || dest == null || src.isDeleted() || dest.isDeleted())
      return;

    HashSet<VDTransactionNode> neighbours = graph.get(src);

    if(neighbours == null) neighbours = new HashSet<VDTransactionNode>();

    if(neighbours.contains(dest))
      return;

    neighbours.add(dest);
    dest.incNumberOfInEdges();
    graph.put(src, neighbours);

    if(this.isCyclic()){
      this.dump(src.getId(), dest.getId());
      neighbours.remove(dest);
      dest.decNumberOfInEdges();
      graph.put(src, neighbours);

      if (methodsViolatingAtomicity != null)
        methodsViolatingAtomicity.add(dest.getMethodInfo());
      return;
    }
  }

  /**
   * For garbage collection root is the given node
   * then the BFS traversal done to identify the
   * nodes with no parent these will be Garbage Collected
   * @param node
   * @return void (VDTransactionGraph will be modified)
   */
  public synchronized void GarbageCollection( VDTransactionNode root){

    Queue<VDTransactionNode> bfsQ = new LinkedList<VDTransactionNode>();
    bfsQ.add(root);

    while(!bfsQ.isEmpty()){

      VDTransactionNode parent = bfsQ.poll();                                   /** remove the parent and add the childern to the list */

      if( parent.isFinished() && parent.getNumberOfInEdges()==0 && !parent.isDeleted()){             /** enqueing the children */
        HashSet<VDTransactionNode> neighbours = graph.get(parent);

        if(neighbours != null){
          for(VDTransactionNode child: neighbours) {
            child.decNumberOfInEdges();
            bfsQ.add(child);
          }
        }

        graph.remove(parent);
        parent.txnDelete();
      }
    }

  }

  /**
   * Check for a cycle in the graph
   */
  public synchronized boolean isCyclic() {
    
    HashSet<VDTransactionNode> visited = new HashSet<>();
    HashSet<VDTransactionNode> active = new HashSet<>();

    for(VDTransactionNode node : graph.keySet()){
      if(dfsUtil(node, visited, active)){
        return true;
      }
    }

    return false;
  }

  /**
   * Utility function for DFS traversal
   * @param node
   * @return
   */
  private boolean dfsUtil(VDTransactionNode node, 
    HashSet<VDTransactionNode> visited, 
    HashSet<VDTransactionNode> active){
    
    if(!visited.contains(node)){  
      HashSet<VDTransactionNode> neighbours = graph.get(node);

      visited.add(node);
      active.add(node);

      if(neighbours == null){
        // System.out.println("return false " + node.getLabel());
        active.remove(node);
        return false;
      }

      for(VDTransactionNode neighbour : neighbours ){
        if(!visited.contains(neighbour) && 
          dfsUtil(neighbour, visited, active)){
          return true;
        }else if(active.contains(neighbour)){
          return true;
        }
      }
    }
    active.remove(node);
    // System.out.println("return false " + node.getLabel());
    return false;
  }

  /**
   * find a possible happens-after node (or a completely new node)
   * for the given list of nodes and update graph accordingly
   */
  public synchronized VDTransactionNode merge(List<VDTransactionNode> mergeInputNodes,
    Integer label,
    String nodeName,
    int tid) {

    // remove all the null nodes and logically deleted nodes from input nodes
    for (int i = 0; i < mergeInputNodes.size(); i++) {
      if (mergeInputNodes.get(i) != null && mergeInputNodes.get(i).isDeleted()) {
        mergeInputNodes.set(i, null);
      }
    }
    mergeInputNodes.removeAll(Collections.singletonList(null));
    
    if (mergeInputNodes.size() == 0) return null;

    VDTransactionNode happensAfterNode = getHappensAfterNode(mergeInputNodes);
    if (happensAfterNode != null) {
      return happensAfterNode;
    }
    
    VDTransactionNode newUnaryNode;
    synchronized (label) {
      newUnaryNode = new VDTransactionNode(label, nodeName, tid, null);
      label += 1;
    }

    for(VDTransactionNode node: mergeInputNodes) {
      addEdge(node, newUnaryNode, null);
    }

    return newUnaryNode;
  }

  /**
   * returns a happens-after node if possible or null
   */
  public VDTransactionNode getHappensAfterNode(List<VDTransactionNode> mergeInputNodes) {

    HashMap<VDTransactionNode, HashSet<VDTransactionNode> > graphReverse = getReverseGraph();

    HashSet<VDTransactionNode> notPossibleHappensBeforeNodes = new HashSet<>();
    HashSet<VDTransactionNode> visited = new HashSet<>();

    for (VDTransactionNode possibleHappensBeforeNode : mergeInputNodes) {
      if (notPossibleHappensBeforeNodes.contains(possibleHappensBeforeNode))
        continue;

      visited.clear();
      dfsUnaryNodes(graphReverse, possibleHappensBeforeNode, visited);

      boolean isSink = true;
      for (VDTransactionNode node : mergeInputNodes) {
        if (visited.contains(node)) {
          notPossibleHappensBeforeNodes.add(node);
        }
        else {
          isSink = false;
        }
      }

      if (isSink) {
        return possibleHappensBeforeNode;
      }
    }

    return null;
  }

  /**
   * dfs for unary transactions optimization
   */
  public void dfsUnaryNodes(HashMap<VDTransactionNode, HashSet<VDTransactionNode> > graphReverse,
    VDTransactionNode node,
    HashSet<VDTransactionNode> visited) {

    visited.add(node);

    HashSet<VDTransactionNode> neighbors = graphReverse.get(node);

    if (neighbors == null)
      return;
      
    for (VDTransactionNode neighbor : neighbors) {
      if (!visited.contains(neighbor)) {
        dfsUnaryNodes(graphReverse, neighbor, visited);
      }
    }

  }

  /**
   * construct reverse of graph
   */
  public HashMap<VDTransactionNode, HashSet<VDTransactionNode>> getReverseGraph() {

    HashMap<VDTransactionNode, HashSet<VDTransactionNode> > graphReverse = new HashMap<>();
    for (Map.Entry<VDTransactionNode, HashSet<VDTransactionNode>> entry : graph.entrySet()) {

      VDTransactionNode src = (VDTransactionNode)entry.getKey();
      HashSet<VDTransactionNode> neighbors = (HashSet<VDTransactionNode>)entry.getValue();

      if (neighbors == null) continue;
      
      for (VDTransactionNode neighbor : neighbors) {

        HashSet<VDTransactionNode> reverseNeighbours = graphReverse.get(neighbor);

        if(reverseNeighbours == null) reverseNeighbours = new HashSet<VDTransactionNode>();
    
        reverseNeighbours.add(src);
        graphReverse.put(neighbor, reverseNeighbours); 
      }
    }

    return graphReverse;
  }

  /**
   * Dump the whole graph into a file in DOT format
   */
  public synchronized void dump(String srcId, String destId){
    
    try {
      File outfile = new File(srcId + "_" + destId + "cycle.dot");
      
      if(outfile.exists())      
        outfile.delete();


      if(outfile.createNewFile()){
        System.err.println("File created " + outfile.getName());
      }else{
        System.err.println("Erorr in file creation");
      }
      
      FileWriter fout = new FileWriter(outfile);
      fout.write("digraph G { \n");

      // configure display of graph
      fout.write(destId + "[shape=diamond, penwidth=3, style=filled, fillcolor=\"#9ACEEB\"];\n");

      for (Map.Entry<VDTransactionNode, HashSet<VDTransactionNode>> entry : graph.entrySet()) {
        VDTransactionNode node1 = (VDTransactionNode)entry.getKey();
        HashSet<VDTransactionNode> edges = (HashSet<VDTransactionNode>)entry.getValue();

        
        if(edges == null)
          continue;

        for(VDTransactionNode node2 : edges ){

          String graphConfigure = "";
          if (node1.getId() == srcId && node2.getId() == destId) {
            graphConfigure = "[penwidth=5]";
          }
          fout.write("  " + node1.getId() + " -> " + node2.getId() + graphConfigure + ";\n");
        }
      }

      fout.write("} \n");

      fout.close();
      
    } catch (IOException e) {
      System.out.println("An error occurred while dumping transaction graph.");
      e.printStackTrace();
    }
  }

}
