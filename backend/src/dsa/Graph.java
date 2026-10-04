package dsa;

import java.util.ArrayList;
import java.util.List;

/**
 * DSA CONCEPT 1 of 3: a directed graph, written from scratch with an
 * ADJACENCY-LIST representation.
 *
 * Why adjacency list (and not an adjacency matrix):
 *   - Airport checkpoint graphs are sparse: 5-6 nodes but only 4-5 legal
 *     transitions. A matrix would waste O(V^2) space on a handful of edges.
 *   - Adding a checkpoint must not force a full matrix rebuild. With lists we
 *     simply add one bucket (O(1) average), which matters because the process
 *     graph is CONFIGURABLE at runtime (see data/transitions.json).
 *   - Iterating the neighbours of a checkpoint - exactly what BFS/DFS and
 *     transition checking do - costs O(out-degree) instead of O(V).
 *
 * The adjacency list is stored in this project's own CustomHashTable, so the
 * graph does not hide behind java.util.HashMap.
 *
 * THE GRAPH IS DATA, NOT CODE. BaggageValidator never contains rules such as
 * "if previous == SECURITY && next == ARRIVAL then reject". It asks the graph
 * whether the edge exists, so changing data/transitions.json changes the rules.
 *
 * Complexity (V = checkpoints, E = legal transitions):
 *   addCheckpoint            O(1) average (hash insert)
 *   addEdge                  O(out-degree) average - a duplicate-edge scan
 *   hasCheckpoint            O(1) average, O(V) worst case
 *   hasEdge                  O(out-degree of 'from') = O(1) for this domain
 *   neighbors                O(out-degree)
 *   BFS (bfsOrder/bfsPath)   O(V + E) time, O(V) space
 *   DFS (dfsOrder/dfsReachable) O(V + E) time, O(V) space (recursion depth O(V))
 */
public class Graph {

    /** checkpoint id -> ordered list of checkpoints reachable by ONE legal transition */
    private final CustomHashTable<List<String>> adjacency;
    /** insertion order, so visualisation and iteration are deterministic */
    private final List<String> checkpointOrder = new ArrayList<String>();
    private int edgeCount;

    public Graph() {
        this(32);
    }

    public Graph(int initialCapacity) {
        this.adjacency = new CustomHashTable<List<String>>(initialCapacity);
    }

    // ------------------------------------------------------------------
    // Construction
    // ------------------------------------------------------------------

    /** Add a checkpoint (node) if it is not already present. */
    public void addCheckpoint(String id) {
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("checkpoint id must not be empty");
        }
        if (!adjacency.contains(id)) {
            adjacency.put(id, new ArrayList<String>());
            checkpointOrder.add(id);
        }
    }

    /** Add a directed transition (edge) from -> to. Missing endpoints are created automatically. */
    public void addEdge(String from, String to) {
        addCheckpoint(from);
        addCheckpoint(to);
        List<String> neighbours = adjacency.get(from);
        if (!neighbours.contains(to)) {   // no duplicate edges, no self-loop handling needed
            neighbours.add(to);
            edgeCount++;
        }
    }

    public boolean hasCheckpoint(String id) {
        return id != null && adjacency.contains(id);
    }

    /** The single question the validator asks for every scan: is current -> next a legal transition? */
    public boolean hasEdge(String from, String to) {
        if (from == null || to == null || !adjacency.contains(from)) {
            return false;
        }
        for (String neighbour : adjacency.get(from)) {
            if (neighbour.equals(to)) {
                return true;
            }
        }
        return false;
    }

    /** Copy of the adjacent checkpoints of 'id' (empty list when the node is unknown). */
    public List<String> neighbors(String id) {
        List<String> neighbours = adjacency.get(id);
        return neighbours == null ? new ArrayList<String>() : new ArrayList<String>(neighbours);
    }

    public List<String> checkpoints() {
        return new ArrayList<String>(checkpointOrder);
    }

    public int nodeCount() {
        return checkpointOrder.size();
    }

    public int edgeCount() {
        return edgeCount;
    }

    public int outDegree(String id) {
        return neighbors(id).size();
    }

    // ------------------------------------------------------------------
    // Traversal: breadth-first search
    // ------------------------------------------------------------------

    /**
     * BFS visit order from 'start'. Uses the project's CustomQueue (FIFO) and the
     * CustomHashTable as the visited-set, so no java.util.Queue/HashSet is used.
     * O(V + E).
     */
    public List<String> bfsOrder(String start) {
        List<String> visitedOrder = new ArrayList<String>();
        if (!hasCheckpoint(start)) {
            return visitedOrder;
        }
        CustomHashTable<Boolean> visited = new CustomHashTable<Boolean>();
        CustomQueue<String> queue = new CustomQueue<String>();
        visited.put(start, Boolean.TRUE);
        queue.enqueue(start);
        while (!queue.isEmpty()) {
            String current = queue.dequeue();
            visitedOrder.add(current);
            for (String neighbour : neighbors(current)) {
                if (!visited.contains(neighbour)) {
                    visited.put(neighbour, Boolean.TRUE);
                    queue.enqueue(neighbour);
                }
            }
        }
        return visitedOrder;
    }

    /** True when 'target' can be reached from 'start' through legal transitions. O(V + E). */
    public boolean reachable(String start, String target) {
        return !bfsPath(start, target).isEmpty();
    }

    /**
     * Shortest legal transition path start..target (inclusive), found with BFS.
     * Returns an empty list when the target is unreachable.
     *
     * BFS is used because each legal transition has the same "cost" (one hop), so
     * BFS returns a path with the fewest checkpoints. It also doubles as the
     * reachability proof: if BFS never reaches ARRIVAL, ARRIVAL is unreachable.
     */
    public List<String> bfsPath(String start, String target) {
        List<String> path = new ArrayList<String>();
        if (!hasCheckpoint(start) || !hasCheckpoint(target)) {
            return path;
        }
        if (start.equals(target)) {
            path.add(start);
            return path;
        }
        CustomHashTable<Boolean> visited = new CustomHashTable<Boolean>();
        CustomHashTable<String> parent = new CustomHashTable<String>();
        CustomQueue<String> queue = new CustomQueue<String>();
        visited.put(start, Boolean.TRUE);
        queue.enqueue(start);
        while (!queue.isEmpty()) {
            String current = queue.dequeue();
            for (String neighbour : neighbors(current)) {
                if (visited.contains(neighbour)) {
                    continue;
                }
                visited.put(neighbour, Boolean.TRUE);
                parent.put(neighbour, current);
                if (neighbour.equals(target)) {
                    // walk the parent chain backwards, then reverse
                    List<String> reversed = new ArrayList<String>();
                    String node = target;
                    while (node != null) {
                        reversed.add(node);
                        node = node.equals(start) ? null : parent.get(node);
                    }
                    for (int i = reversed.size() - 1; i >= 0; i--) {
                        path.add(reversed.get(i));
                    }
                    return path;
                }
                queue.enqueue(neighbour);
            }
        }
        return path;   // empty: target unreachable
    }

    // ------------------------------------------------------------------
    // Traversal: depth-first search (recursive, explicit visited-set)
    // ------------------------------------------------------------------

    /**
     * DFS pre-order visit order from 'start'. O(V + E).
     * Included so reachability can be cross-checked with a second traversal
     * strategy, and because the course requires both to be demonstrated.
     */
    public List<String> dfsOrder(String start) {
        List<String> order = new ArrayList<String>();
        if (!hasCheckpoint(start)) {
            return order;
        }
        dfs(start, new CustomHashTable<Boolean>(), order);
        return order;
    }

    /** Reachability via DFS. Same answer as BFS, different search order. O(V + E). */
    public boolean dfsReachable(String start, String target) {
        if (!hasCheckpoint(start) || !hasCheckpoint(target)) {
            return false;
        }
        if (start.equals(target)) {
            return true;
        }
        CustomHashTable<Boolean> visited = new CustomHashTable<Boolean>();
        visited.put(start, Boolean.TRUE);
        return dfsFind(start, target, visited);
    }

    private boolean dfsFind(String current, String target, CustomHashTable<Boolean> visited) {
        for (String neighbour : neighbors(current)) {
            if (neighbour.equals(target)) {
                return true;
            }
            if (!visited.contains(neighbour)) {
                visited.put(neighbour, Boolean.TRUE);
                if (dfsFind(neighbour, target, visited)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void dfs(String current, CustomHashTable<Boolean> visited, List<String> order) {
        visited.put(current, Boolean.TRUE);
        order.add(current);
        for (String neighbour : neighbors(current)) {
            if (!visited.contains(neighbour)) {
                dfs(neighbour, visited, order);
            }
        }
    }

    /** checkpoints that cannot reach ARRIVAL (used to explain an ARRIVAL UNREACHABLE finding). */
    public List<String> nodesThatCannotReach(String target) {
        List<String> stuck = new ArrayList<String>();
        for (String id : checkpointOrder) {
            if (!reachable(id, target)) {
                stuck.add(id);
            }
        }
        return stuck;
    }
}
