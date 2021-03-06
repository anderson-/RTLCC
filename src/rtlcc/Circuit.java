/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package rtlcc;

import edu.uci.ics.jung.algorithms.layout.FRLayout;
import edu.uci.ics.jung.algorithms.layout.KKLayout;
import edu.uci.ics.jung.algorithms.layout.Layout;
import edu.uci.ics.jung.graph.SparseMultigraph;
import edu.uci.ics.jung.visualization.VisualizationViewer;
import edu.uci.ics.jung.visualization.control.DefaultModalGraphMouse;
import edu.uci.ics.jung.visualization.control.ModalGraphMouse;
import edu.uci.ics.jung.visualization.decorators.ToStringLabeller;
import java.awt.Color;
import java.awt.Dimension;
import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.JFrame;
import org.jenetics.BitChromosome;
import org.jenetics.BitGene;
import org.jenetics.GeneticAlgorithm;
import org.jenetics.Genotype;
import org.jenetics.IntegerChromosome;
import org.jenetics.IntegerGene;
import org.jenetics.Mutator;
import org.jenetics.NumberStatistics;
import org.jenetics.Optimize;
import org.jenetics.RouletteWheelSelector;
import org.jenetics.SinglePointCrossover;
import org.jenetics.StochasticUniversalSelector;
import org.jenetics.SwapMutator;
import org.jenetics.util.Factory;
import org.jenetics.util.Function;
import org.jenetics.util.IO;
import org.jenetics.util.IndexStream;
import org.jenetics.util.MSeq;
import processing.core.PApplet;
import processing.core.PGraphics;
import processing.opengl.PGraphics3D;
import quickp3d.DrawingPanel3D;
import quickp3d.graph.QuickGraph;
import quickp3d.simplegraphics.Axis;
import static rtlcc.Topology.X;
import static rtlcc.Topology.Y;
import static rtlcc.Topology.Z;
import rtlcc.toml.impl.Toml;

/**
 *
 * @author antunes
 */
public class Circuit {

    public ArrayList<Component> vertices;
    public ArrayList<Component> inputs;
    public ArrayList<Component> outputs;
    public static int sleep = 100;
    public static boolean chain = false;
    public static long seed = 1;
    public static Random rand = new Random();
    public static boolean shuffleNg = false;
    public static boolean optimize = false;

    public Circuit() {
        vertices = new ArrayList<>();
        inputs = new ArrayList<>();
        outputs = new ArrayList<>();
    }

    public String save() {
        POJO p = new POJO();
        p.pos = new ArrayList<>();
        p.names = new ArrayList<>();
        for (Component c : vertices) {
            if (c.pos != null) {
                p.names.add(c.getUID());
                ArrayList<Long> pos = new ArrayList<>();
                pos.add(new Long(c.pos[0]));
                pos.add(new Long(c.pos[1]));
                pos.add(new Long(c.pos[2]));
                p.pos.add(pos);
            }
        }
        return Toml.serialize("circuit", p);
    }

    public void load(String str) {

        str = "[circuit]\n"
                + "pos = [[0, 1, 0], [1, 1, 1], [1, 1, 1], [0, 1, 1], [0, 1, 1], [1, 0, 2], [0, 0, 1], [1, 0, 1], [1, 1, 2], [0, 0, 2], [0, 0, 0], [1, 1, 1], [0, 0, 1], [0, 1, 1]]\n"
                + "names = [\"&*65\", \"j1*66\", \"j2*67\", \"j3*68\", \"j4*69\", \"vcc*70\", \"gnd*71\", \"transistor72\", \"&*74\", \"&*77\", \"j*80\", \"ex*100\", \"ex*101\", \"ex*102\"]";

        Toml parser = Toml.parse(str);
        POJO p = parser.getAs("circuit", POJO.class);
        for (int i = 0; i < p.names.size(); i++) {
            for (Component c : vertices) {
                if (c.getUID().equals(p.names.get(i))) {
                    c.pos = new int[3];
                    c.pos[0] = p.pos.get(i).get(0).intValue();
                    c.pos[1] = p.pos.get(i).get(1).intValue();
                    c.pos[2] = p.pos.get(i).get(2).intValue();
                }
            }
        }
    }

    public Component get(String name) {
        for (Component c : vertices) {
            if (c.name != null && c.name.equals(name)) {
                return c;
            }
        }
        return null;
    }

    public void remove(Component c) {
        vertices.remove(c);
        //se é input não é output
        if (!inputs.remove(c)) {
            outputs.remove(c);
        }
    }

    public static Circuit union(Circuit[] cset, String... joints) {
        Circuit nc = new Circuit();

        ArrayList<ArrayList<Component>> toJoin = new ArrayList<>();
        ArrayList<String> jointName = new ArrayList<>();
        ArrayList<Component> temp;
        for (String joint : joints) {
            try {
                temp = new ArrayList<>();
                String s[] = joint.split("->");
                for (String t : s[0].split("\\+")) {
                    t = t.trim();
                    String[] comp = t.split("\\.");
                    int i = Integer.parseInt(comp[0]);
                    String name = comp[1];
                    Component c = cset[i].get(name);
                    if (c == null) {
                        throw new IllegalArgumentException("arg: " + joint + " : " + name + " not found!");
                    }
                    temp.add(c);
                }
                if (s.length > 1) {
                    jointName.add(s[1].trim());
                } else {
                    jointName.add("");
                }
                toJoin.add(temp);
            } catch (Exception e) {
                throw new IllegalArgumentException("arg: " + joint);
            }
        }

        for (int i = 0; i < toJoin.size(); i++) {
            Component c1 = null;
            boolean in = false;
            boolean out = false;
            String name = jointName.get(i);
            if (name.startsWith("in.")) {
                name = name.substring(3);
                in = true;
            } else if (name.startsWith("out.")) {
                name = name.substring(4);
                out = true;
            }
            for (Component c : toJoin.get(i)) {
                if (c1 != null) {
                    c1.joinAndConsume(c);
                } else {
                    c1 = c;
                }
            }

            if (c1 == null) {
                throw new IllegalArgumentException("arg: " + i);
            }

            c1.name = name;

            if (!name.isEmpty()) {
                if (in) {
                    nc.inputs.add(c1);
                }
                if (out) {
                    nc.outputs.add(c1);
                }
            }
        }

        //remove componentes consumidos
//        for (Iterator<Component> it = nc.vertices.iterator(); it.hasNext();) {
//            Component comp = it.next();
//            if (comp.consumed){
//                it.remove();
//            }
//        }
        //adiciona vertices e entradas/saidas (com nome)
        int i = 0;
        for (Circuit c : cset) {
            if (c == null) {
                throw new IllegalArgumentException("arg: Circuit " + i + " == null");
            }
            i++;
            for (Component comp : c.vertices) {
                if (!comp.consumed && !nc.vertices.contains(comp)) {
                    nc.vertices.add(comp);
                }
            }

            for (Component comp : c.inputs) {
                if (!comp.consumed && comp.name != null && !comp.name.isEmpty() && !nc.vertices.contains(comp)) {
                    nc.inputs.add(comp);
                }
            }

            for (Component comp : c.outputs) {
                if (!comp.consumed && comp.name != null && !comp.name.isEmpty() && !nc.outputs.contains(comp)) {
                    nc.outputs.add(comp);
                }
            }
        }

        return nc;
    }

    private static void populateJoint(Component j, Component c, int index) {
        j.ends.add(c);
        j.endTerminals.add(c.terminals.get(index));

        for (Component a : c.connections) {
            if (a.joint && !j.ends.contains(a)) {
                int i = a.connections.indexOf(c);
                populateJoint(j, a, i);
            } else {
                int cai = c.connections.indexOf(a);
                int aci = a.connections.indexOf(c);
                //verifica se é um caminho limpo entre C e A(sem componentes)
                if (c.subComponents.get(cai).isEmpty() && a.subComponents.get(aci).isEmpty()) {
                    if (!j.ends.contains(a)) {
                        j.ends.add(a);
                        j.endTerminals.add(a.terminals.get(aci));
                    }
                }
            }
        }
    }

    public void populateJoints() {
        for (Component c : vertices) {
            if (c.joint) {
                for (Component a : c.connections) {
                    int i = a.connections.indexOf(c);
                    if (!c.ends.contains(a)) {
                        populateJoint(c, a, i);
                    }
                }
            }
        }
    }

    public void decubeficate() {
        reset();
        for (Component c : vertices) {
            c.pos = null;
        }
    }

    public final static int UNVISITED = -1;
    public final static int VISITED = -2;
    public final static int ONQUEUE = -3;

    private int countNg(int[][][] cube, int[] pos, Topology t) {
        int i = 0;
        for (int[] l : t.getNeighborhood(pos)) {
            int p = cube[l[0]][l[1]][l[2]];
            if (p < 0) {
                i++;
            } else {
                i += (p == UNVISITED) ? 1 : 0;
            }
        }
        return i;
    }

    private void whut(Component v, Component j) {
        show2D();
        System.out.println(v + " " + v.getUID());
        for (int i = 0; i < v.connections.size(); i++) {
            System.out.println(i + " " + v.connections.get(i) + " " + v.connections.get(i).getUID());
        }
        System.out.println(j + " " + j.getUID());
        for (int i = 0; i < j.connections.size(); i++) {
            System.out.println(i + " " + j.connections.get(i) + " " + j.connections.get(i).getUID());
        }
    }

    private Component expand(Component v, Component j) {
        int a = v.connections.indexOf(j);
        int b = j.connections.indexOf(v);
        Component n = new Component(true);
        v.connections.set(a, n);
        j.connections.set(b, n);

        n.type = "ex";

        n.connections.add(v);
        n.subComponents.add("");
//        n.subComponents.add(v.subComponents.get(a));
        n.terminals.add("");
        n.doneConnections.add(true);

        n.connections.add(j);
        n.subComponents.add("");
//        n.subComponents.add(j.subComponents.get(b));
        n.terminals.add("");
        n.doneConnections.add(true);

        n.ends.add(v);//adiciona v
        n.endTerminals.add(v.terminals.get(a));//adiciona o terminal em v que j está conectado
        n.ends.add(j);
        n.endTerminals.add(j.terminals.get(b));//adiciona o terminal em j que v está conectado
        if (v.joint) {
            n.ends.addAll(v.ends);
            n.endTerminals.addAll(v.endTerminals);
        }
        if (j.joint) {
            n.ends.addAll(j.ends);
            n.endTerminals.addAll(j.endTerminals);
        }

//        v.subComponents.set(a, "");
//        j.subComponents.set(b, "");
        vertices.add(n);

        sleep();

        return n;
    }

//    private void makePathAndPlace(Component v, Component j, Topology t) {
//
//        int vi = j.connections.indexOf(v);
//        int ji = v.connections.indexOf(j);
//
//        ArrayList<int[]> neighborhood = t.getNeighborhood(v.pos);
//        //            Collections.shuffle(neighborhood, rand);
//        for (int[] w : neighborhood) {
//            if (countNg(w, t) > j.connections.size()) {
//                //define a posição de j
//                j.pos = pos;
//                cube[j.pos[0]][j.pos[1]][j.pos[2]] = vertices.indexOf(j);
//
//                //conecta o caminho
//                int[] i = ini;
//                Component n = v;
//                //System.out.println("place");
//                //System.out.println(map.containsKey(i) + " " + map.size());
//                while (map.containsKey(i)) {
//                    //System.out.println(".");
//                    i = map.get(i);
//                    n = expand(n, j);
//                    n.pos = i;
//                    cube[i[0]][i[1]][i[2]] = vertices.indexOf(n);
//                }
//                v.doneConnections.set(ji, true);
//                j.doneConnections.set(vi, true);
//                return;
//            }
//        }
//    }
    private void placeNg(Component v, Component j, int[][][] cube, Topology t) {
        ArrayDeque<int[]> queue = new ArrayDeque<>();

        ArrayList<int[]> neighborhood = t.getNeighborhood(v.pos);
        if (shuffleNg) {
            Collections.shuffle(neighborhood, rand);
        }
        for (int[] w : neighborhood) {
            if (cube[w[0]][w[1]][w[2]] == UNVISITED) {
                queue.add(w);
            }
        }

        int[] pos;
        while (!queue.isEmpty()) {
            pos = queue.remove();
            if (countNg(cube, pos, t) >= j.connections.size()) {
                j.pos = pos;
                cube[j.pos[0]][j.pos[1]][j.pos[2]] = vertices.indexOf(j);
                int vi = j.connections.indexOf(v);
                int ji = v.connections.indexOf(j);
                v.doneConnections.set(ji, true);
                j.doneConnections.set(vi, true);
            }
        }
    }

    private void makePathAndPlace(Component v, Component j, int[][][] cube, Topology t) {
        ArrayDeque<int[]> queue = new ArrayDeque<>();
//        queue.add(v.pos);
//        queue.addAll(t.getNeighborhood(v.pos));
        for (int[] w : t.getNeighborhood(v.pos)) {
            if (cube[w[0]][w[1]][w[2]] == UNVISITED) {
                queue.add(w);
            }
        }

        int[] ini = v.pos;
        int[] old = null;
        int[] pos = ini;
        int vi = j.connections.indexOf(v);
        int ji = v.connections.indexOf(j);

        HashMap<int[], int[]> map = new HashMap<>();
        while (!queue.isEmpty()) {
            old = pos;
            pos = queue.remove();
            if (old != null) {
                map.put(pos, old);
            }

            ArrayList<int[]> neighborhood = t.getNeighborhood(pos);
            if (shuffleNg) {
                Collections.shuffle(neighborhood, rand);
            }
            for (int[] w : neighborhood) {
                if (cube[w[0]][w[1]][w[2]] == UNVISITED) {
                    queue.add(w);
                    cube[w[0]][w[1]][w[2]] = ONQUEUE;
                }
            }

            int k = cube[pos[0]][pos[1]][pos[2]];
            if (k >= 0) {
                if (vertices.get(k).pos != null) {
                    System.err.println("pessimo erro do mal!");
                }
            }

            if (countNg(cube, pos, t) >= j.connections.size()) {
                //define a posição de j
                j.pos = pos;
                cube[j.pos[0]][j.pos[1]][j.pos[2]] = vertices.indexOf(j);

                //conecta o caminho
                int[] i = ini;
                Component n = v;
                //System.out.println("place");
                //System.out.println(map.containsKey(i) + " " + map.size());
                while (map.containsKey(i)) {
                    //System.out.println(".");
                    i = map.get(i);
                    n = expand(n, j);
                    n.pos = i;
                    cube[i[0]][i[1]][i[2]] = vertices.indexOf(n);
                }
                v.doneConnections.set(ji, true);
                j.doneConnections.set(vi, true);
                return;
            }
        }
    }

    public List<int[]> getDirections(int[] sourceNode, int[] destinationNode, Topology t) {
        //Initialization.
        Map<int[], int[]> nextNodeMap = new TreeMap<>(new Comparator<int[]>() {
            @Override
            public int compare(int[] o1, int[] o2) {
                return Arrays.equals(o1, o2) ? 0 : 1;
            }
        });
        int[] currentNode = sourceNode;

        //Queue
        Queue<int[]> queue = new LinkedList<int[]>();
        queue.add(currentNode);

        /*
         * The set of visited nodes doesn't have to be a Map, and, since order
         * is not important, an ordered collection is not needed. HashSet is 
         * fast for add and lookup, if configured properly.
         */
        Set<int[]> visitedNodes = new TreeSet<>(new Comparator<int[]>() {
            @Override
            public int compare(int[] o1, int[] o2) {
                boolean s = Arrays.equals(o1, o2);
                if (s) {
//                    System.out.println(Arrays.toString(o1) + " == " + Arrays.toString(o2));
                }

                return s ? 0 : 1;
            }
        });
        visitedNodes.add(currentNode);

        //Search.
        while (!queue.isEmpty()) {
//            System.out.println("q:" + queue.size());
            currentNode = queue.remove();
            if (Arrays.equals(currentNode, destinationNode)) {
                break;
            } else {
                for (int[] nextNode : t.getNeighborhood(currentNode)) {
                    if (!visitedNodes.contains(nextNode)) {
                        boolean empty = true;
//                        for (Component c : vertices) {
//                            if (c.pos != null && Arrays.equals(nextNode, c.pos)) {
//                                if (Arrays.equals(nextNode, destinationNode)){
//                                    
//                                } else {
//                                    empty = false;
//                                    visitedNodes.add(nextNode);
//                                }
//                            }
//                        }

                        if (empty) {
                            queue.add(nextNode);
                            visitedNodes.add(nextNode);

                            //Look up of next node instead of previous.
                            nextNodeMap.put(nextNode, currentNode);

                        }
                    }

                }
            }
        }

        //If all nodes are explored and the destination node hasn't been found.
        if (!Arrays.equals(currentNode, destinationNode)) {
            throw new RuntimeException("No feasible path.");
        }

        //Reconstruct path. No need to reverse.
        List<int[]> directions = new LinkedList<int[]>();
        for (int[] node = destinationNode; node != null; node = nextNodeMap.get(node)) {
            if (!Arrays.equals(node, destinationNode) && !Arrays.equals(node, sourceNode)) {
                directions.add(node);
            }
        }

        return directions;
    }

    public int getDisconectedConnections() {
        int dc = 0;
        for (Component c : (ArrayList<Component>) vertices.clone()) {
            for (boolean b : c.doneConnections) {
                if (!b) {
                    dc++;
                }
            }
        }
        return dc / 2;
    }

    public int getVolume() {
        int x0 = Integer.MAX_VALUE;
        int y0 = Integer.MAX_VALUE;
        int z0 = Integer.MAX_VALUE;

        int x1 = 0;
        int y1 = 0;
        int z1 = 0;

        int x, y, z;

        for (Component c : vertices) {
            if (c.pos == null) {
                continue;
            }

            x = c.pos[0];
            y = c.pos[1];
            z = c.pos[2];

            if (x < x0) {
                x0 = x;
            }
            if (x > x1) {
                x1 = x;
            }
            if (y < y0) {
                y0 = y;
            }
            if (y > y1) {
                y1 = y;
            }
            if (z < z0) {
                z0 = z;
            }
            if (z > z1) {
                z1 = z;
            }
        }

        return (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1);
    }

    static class Dijkstra {

        static int toInt(int x, int y, int z) {
            int rgb = x;
            rgb = (rgb << 8) + y;
            rgb = (rgb << 8) + z;
            return rgb;
        }

        static int toInt(int... i) {
            int rgb = i[0];
            rgb = (rgb << 8) + i[1];
            rgb = (rgb << 8) + i[2];
            return rgb;
        }

        static int[] toVet(int i) {
            int x = (i >> 16) & 0xFF;
            int y = (i >> 8) & 0xFF;
            int z = i & 0xFF;
            return new int[]{x, y, z};
        }

        Map<Integer, Integer> distances = new HashMap<>();

        Map<Integer, Integer> prev = new HashMap<>();

        int source = -1;
        int target = -1;

        public void computePaths(int[] source, int[] target, Topology t, List<Component> vertices, List<Integer> validShortcuts) {
            this.source = toInt(source);
            this.target = toInt(target);

            for (Component c : vertices) {
                if (c.pos != null) {
                    int i = toInt(c.pos);
                    if (i != this.source && i != this.target) {
                        if (!validShortcuts.contains(i)) {
                            distances.put(i, 12000);
                        } else {
                            distances.put(i, 12000);
                        }
                    }
                }
            }

            distances.put(this.source, 0);
            LinkedList<Integer> vertexQueue = new LinkedList<>();
            vertexQueue.add(toInt(source));

            while (!vertexQueue.isEmpty()) {
                int u = vertexQueue.poll();

                // Visit each edge exiting u
                for (int[] vv : t.getNeighborhood(toVet(u))) {
                    int v = toInt(vv);
                    Integer distV = distances.get(v);
                    if (distV == null) {
                        distV = 9000;
                    }

                    Integer distU = distances.get(u);
                    if (distU == null) {
                        distU = 9000;
                    }

                    int distanceThroughU = distU + 1;
                    if (distanceThroughU < distV && distV != 12000) {
                        vertexQueue.remove((Integer) v);
                        distances.put(v, distanceThroughU);
                        prev.put(v, u);
                        vertexQueue.add(v);
                    }
                }
            }
        }

        public List<int[]> getShortestPathTo(int[] target) {
            List<int[]> path = new ArrayList<>();
            for (Integer vertex = toInt(target); vertex != null; vertex = prev.get(vertex)) {
                if (vertex != toInt(target) && vertex != source) {
                    path.add(toVet(vertex));
                }
            }
            Collections.reverse(path);
            return path;
        }
    }

//    
//    public List<Node> getDirections2(Node start, Node finish){
//    List<Node> directions = new LinkedList<Node>();
//    Queue<Node> q = new LinkedList<Node>();
//    Node current = start;
//    q.add(current);
//    while(!q.isEmpty()){
//        current = q.remove();
//        directions.add(current);
//        if (current.equals(finish)){
//            break;
//        }else{
//            for(Node node : current.getOutNodes()){
//                if(!q.contains(node)){
//                    q.add(node);
//                }
//            }
//        }
//    }
//    if (!current.equals(finish)){
//        System.out.println("can't reach destination");
//    }
//    return directions;
//}
//    
    boolean isValidShortcut(Component c, Component v, Component j) {
        //c é valido se eu quero chegar em j a partir de v: "v -> c -> J"
        if (c.joint) {//só se c for uma junta
            int i = c.ends.indexOf(j);
            if (i != -1) {
                if (j.joint) {//e j também (não importa os terminais)
                    return true;
                } else {
                    if (optimize) {
                        int vi = j.connections.indexOf(v);
                        String term = j.terminals.get(vi);
                        if (term.isEmpty()) {
                            int ji = v.connections.indexOf(j);
                            term = v.terminals.get(ji);
                        }
                        if ((c.endTerminals.get(i).equals(term))) {
                            System.out.println("FOIIIIIIIIIIIII");
                        }
                        return (c.endTerminals.get(i).equals(term));
                    }
                }
            }
        }
        return false;
    }

    public boolean makePathTo(Component v, Component j, Topology t) {
        int vi = j.connections.indexOf(v);
        int ji = v.connections.indexOf(j);

        //se for vizinha
        for (int[] ng : t.getNeighborhood(v.pos)) {
            if (Arrays.equals(ng, j.pos)) {
                v.doneConnections.set(ji, true);
                j.doneConnections.set(vi, true);
                return true;
            }
        }

        try {
            //System.out.println("find");

            if (vi == -1 || ji == -1) {
                System.out.println("nooooooooooooot" + v.getUID() + " " + j.getUID());
            }

            Dijkstra d = new Dijkstra();

            ArrayList<Integer> validShortcutsPos = new ArrayList<>();
            ArrayList<Component> validShortcuts = new ArrayList<>();
            for (Component c : vertices) {
                if (c.pos != null && isValidShortcut(c, v, j)) {
                    validShortcutsPos.add(Dijkstra.toInt(c.pos));
                    validShortcuts.add(c);
                }
            }

            d.computePaths(v.pos, j.pos, t, vertices, validShortcutsPos);
            List<int[]> directions = null, dt;
            Component f = null;
            System.out.println("validShortcuts: " + validShortcuts.size());
            for (Component c : vertices) {
                if (c == j || validShortcuts.contains(c)) {
                    dt = d.getShortestPathTo(c.pos);
//                    System.out.print(dt.size() + ",");
                    if ((directions == null || dt.size() < directions.size()) && dt.size() > 0) {
                        directions = dt;
                        f = c;
                    }
                }
            }
//            System.out.println("");

            boolean pathExpanded = false;
            boolean shortcut = false;//foi feito um atalho

            if (directions != null) {
                System.out.println("exp: " + directions.size());
                Component n = v;
                for (int[] c : directions) {
                    //System.out.println("*");
                    n = expand(n, j);
                    //definir que todas as juntas compartilham os mesmos ends
                    n.pos = c;
                    pathExpanded = true;
                }

//                if (f.joint) {
////                    n.type = "jj";
////                    f.type = "w";
//                    int tni = j.connections.indexOf(n);
//                    int tji = n.connections.indexOf(j);
//
//                    //j -/-> n
//                    j.connections.remove(tni);
//                    j.subComponents.remove(tni);
//                    j.terminals.remove(tni);
//                    j.doneConnections.remove(tni);
//                    shortcut = true;
//
//                    //n -/-> j
//                    n.connections.remove(tji);
//                    n.subComponents.remove(tji);
//                    n.terminals.remove(tji);
//                    n.doneConnections.remove(tji);
//
//                    //n -> f
//                    n.connections.add(f);
//                    n.subComponents.add("");
//                    n.terminals.add("");
//                    n.doneConnections.add(true);
//
//                    //f -> n
//                    f.connections.add(n);
//                    f.subComponents.add("");
//                    f.terminals.add("");
//                    f.doneConnections.add(true);
//                }
            }

            if (pathExpanded) {
                v.doneConnections.set(ji, true);
                j.doneConnections.set(vi, true);
                return true;
            } else {
                return false;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }

//        ArrayDeque<int[]> queue = new ArrayDeque<>();
////        queue.add(v.pos);
////        queue.addAll(t.getNeighborhood(v.pos));
//
//        for (int[] w : t.getNeighborhood(v.pos)) {
//            if (cube[w[0]][w[1]][w[2]] == UNVISITED) {
//                queue.add(w);
//            }
//        }
//
//        int[] ini = v.pos;
//        int[] old = null;
//        int[] current = ini;
//        int vi = j.connections.indexOf(v);
//        int ji = v.connections.indexOf(j);
//
//        Map<int[], int[]> prev = new TreeMap<>(new Comparator<int[]>() {
//            @Override
//            public int compare(int[] o1, int[] o2) {
//                return Arrays.equals(o1, o2) ? 0 : 1;
//            }
//        });
//
//        while (!queue.isEmpty()) {
////            System.out.println("find: " + queue.size());
//
//            old = current;
//            current = queue.remove();
//            if (old != null) {
//                prev.put(current, old);
//            }
//            
//            if (!prev.containsKey(old)) {
//                prev.put(old, current);
//                q.add(node);
//            }
//
//            for (int[] w : t.getNeighborhood(current)) {
//                if (Arrays.equals(w, v.pos)) {
//                    continue;
//                }
//                int p = cube[w[0]][w[1]][w[2]];
//                if (p == UNVISITED) {
//                    queue.add(w);
//                    cube[w[0]][w[1]][w[2]] = ONQUEUE;
//
//                }
//                if (p >= 0 && (vertices.get(p) == j || (vertices.get(p).connections.contains(j))
//                        || (vertices.get(p).joint && vertices.get(p).ends.contains(j)))) {
//
////                    map.put(w, pos);
//                    //conecta o caminho até j
//                    int[] i = current;
//                    int[] o = null;
//                    Component n = v;
//                    System.out.println("find");
//                    System.out.println(prev.containsKey(i) + " " + prev.size());
//                    if (prev.containsKey(i)) {
//                        mapToGraph2D(prev);
//                        while (prev.containsKey(i)) {
//                            System.out.println(".");
//                            sleep();
////                            if (Arrays.equals(i, v.pos)) {
////                                System.out.println("exit");
////                                break;
////                            }
//                            o = i;
//                            i = prev.get(i);
//                            n = expand(n, j);
//
//                            if (o != null) {
//                                boolean ok = false;
//                                for (int[] ng : t.getNeighborhood(o)) {
//                                    if (Arrays.equals(ng, i)) {
//                                        ok = true;
//                                        break;
//                                    }
//                                }
//                                if (!ok) {
//                                    System.out.println(Arrays.toString(i) + " is not next to " + Arrays.toString(o));
//                                }
//                            }
//
//                            n.pos = i;
//                            cube[i[0]][i[1]][i[2]] = vertices.indexOf(n);
//                        }
////                        n = expand(n, j);
////                        n.pos = i;
////                        cube[i[0]][i[1]][i[2]] = vertices.indexOf(n);
//
//                        v.doneConnections.set(ji, true);
//                        j.doneConnections.set(vi, true);
//                        return true;
//                    }
//                }
//            }
//        }
//        return false;
    }

    public void resetCube(int[][][] cube) {
        for (int x = 0; x < X; x++) {
            for (int y = 0; y < Y; y++) {
                for (int z = 0; z < Z; z++) {
                    cube[x][y][z] = UNVISITED;
                }
            }
        }
        int i = 0;
        for (Component v : vertices) {
            if (v.pos != null) {
                cube[v.pos[0]][v.pos[1]][v.pos[2]] = i;
            }
            i++;
        }
    }

    public void sleep() {
        try {
            Thread.sleep(sleep);
        } catch (InterruptedException ex) {

        }
    }

    public void reset() {
        ArrayList<Component> old = new ArrayList<>(vertices);
        vertices.clear();
        for (Component c : old) {
            if (c.fixed) {
                c.reset();
                vertices.add(c);
            }
        }
    }

    public void cubeficate(Topology t) {
        if (shuffleNg) {
            rand.setSeed(seed);
        }

        //guarda a posição no vetor vertices
        int[][][] cube = new int[X][Y][Z];
        resetCube(cube);
        boolean done = false;
        int count = 0;
        while (!done) {
            if (count > 500) {
                System.out.println("I'M DONE!!!!500");
                break;
            }
            count++;
            done = true;
            for (int vi = 0; vi < vertices.size(); vi++) {
                Component v = vertices.get(vi);
                if (v.pos == null) {
                    done = false;
                } else {
                    int i = 0;
                    for (Component j : v.connections) {
                        if (j.pos == null) {
                            /*
                             coloca j no lugar mais proximo, com expansão 
                             de caminhos se necessario;
                             */
                            resetCube(cube);
                            placeNg(v, j, cube, t);
                            //makePathAndPlace(v, j, cube, t);
                            sleep();
                            if (!chain) {
                                break;
                            }
                        } else {
                            if (!v.doneConnections.get(i)) {
                                /*
                                 procura o no mais proximo* para satisfazer a 
                                 conexão;
                                 */
                                if (makePathTo(v, j, t)) {
                                    sleep();
                                } else {
                                    System.out.println(v.getUID() + " -/-> " + j.getUID());
                                }
                                if (!chain) {
                                    break;
                                }
                            }
                        }
                        i++;
                    }
                }
            }
        }
    }

    public int bits = 3;//7

    public void placeComponentsByGenotype(Genotype<IntegerGene> genotype, Topology t) {
        decubeficate();
        IntegerChromosome ch;
        vit:
        for (int i = 0; i < vertices.size(); i++) {
            int[] p = new int[3];
            for (int j = 0; j < 3; j++) {
                ch = ((IntegerChromosome) genotype.getChromosome(j));
                p[j] = ch.getGene(i).getAllele();
            }
            for (Component w : vertices) {
                if (w.pos != null && Arrays.equals(w.pos, p)) {
                    continue vit;
                }
            }

            vertices.get(i).pos = p;
        }
    }

    static class CircuitFitnessFunction implements Function<Genotype<IntegerGene>, Integer> {

        public int bits;
        public static int count = 0;
        public Topology t;
        public CircuitBuilder cb;

        private CircuitFitnessFunction(Topology t, int bits, CircuitBuilder cb) {
            this.t = t;
            this.bits = bits;
            this.cb = cb;
        }

        @Override
        public Integer apply(final Genotype<IntegerGene> genotype) {

            long time = System.currentTimeMillis();
            Circuit c = cb.build();
            int n1 = c.vertices.size();
            c.placeComponentsByGenotype(genotype, Topology.SIMPLE3);
            c.cubeficate(Topology.SIMPLE);
            c.cubeficate(Topology.SIMPLE2);
            c.cubeficate(Topology.SIMPLE3);
            count++;
            int dc = c.getDisconectedConnections();
            int v = c.getVolume();
            int n2 = c.vertices.size();
            int p = fitnessFunction(n1, n2, dc, v);
            System.out.println("done " + count + " in " + (System.currentTimeMillis() - time) + " ms, vertices: " + n1 + " -> " + n2 + ", disc: " + dc + ", v:" + v + " | p: " + p);
            System.gc();
            return p;
        }
    }

    public static int fitnessFunction(int minNodes, int nodes, int disconected, int volume) {
        int p = disconected * 100000 + (nodes - minNodes) * 1000 + volume;
        return p;
    }

    public void geneticPlaceComponents(int pop, int gen, Topology t, CircuitBuilder cb) {
        int sleepOld = sleep;
        sleep = 0;

        ArrayList<IntegerChromosome> list = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            list.add(IntegerChromosome.of(0, X - 1, vertices.size()));
            list.add(IntegerChromosome.of(0, Y - 1, vertices.size()));
            list.add(IntegerChromosome.of(0, Z - 1, vertices.size()));
        }

        Factory<Genotype<IntegerGene>> gtf = Genotype.of(list.toArray(new IntegerChromosome[list.size()]));

        CircuitFitnessFunction cff = new CircuitFitnessFunction(t, bits, cb);

        GeneticAlgorithm<IntegerGene, Integer> ga
                = new GeneticAlgorithm<>(
                        gtf, cff, Optimize.MINIMUM
                );

        ga.setStatisticsCalculator(
                new NumberStatistics.Calculator<IntegerGene, Integer>()
        );
        ga.setPopulationSize(pop);

        ga.setSelectors(
                new RouletteWheelSelector<IntegerGene, Integer>()
        //,
        //new StochasticUniversalSelector<IntegerGene, Integer>()
        );
        ga.setAlterers(
                //new Mutator<IntegerGene>(0.4),
                new Mutator<IntegerGene>(0.2) {

                    @Override
                    protected int mutate(MSeq<IntegerGene> genes, double p) {
                        final IndexStream stream = IndexStream.Random(genes.length(), p);

                        int alterations = 0;
                        for (int i = stream.next(); i != -1 && alterations < 3; i = stream.next()) {
                            IntegerGene ig = genes.get(i);
                            int v = ig.getAllele();
                            if (v == ig.getMax()) {
                                v--;
                            } else if (v == 0) {
                                v++;
                            } else {
                                v += (rand.nextDouble() > .5) ? 1 : -1;
                            }
                            genes.set(i, new IntegerGene(v, ig.getMin(), ig.getMax()));
                            ++alterations;
                        }
                        return alterations;
                    }

                },
                //new SinglePointCrossover<IntegerGene>(0.1),
                new SwapMutator<IntegerGene>(0.1)
        );

        ga.setup();
        ga.evolve(gen);

        try {
            final File file = new File("population" + System.currentTimeMillis() % 10000 + ".xml");
            IO.jaxb.write(ga.getPopulation(), file);
        } catch (IOException ex) {
            System.err.println("falha ao gravar população em disco");
        }
        System.out.println(ga.getBestStatistics());
        System.out.println(ga.getTimeStatistics());
        System.out.println(ga.getBestPhenotype());
        System.out.println("" + (this.vertices.size() * bits));
        placeComponentsByGenotype(ga.getBestPhenotype().getGenotype(), t);
        sleep = sleepOld;
    }

    public void show2D() {
        SparseMultigraph<String, String> graph = new SparseMultigraph<>();

        //adiciona vertices
        for (Component v : vertices) {
            graph.addVertex(v.getUID());
        }

        //adciona arestas
        int id = 0;
        for (Component v : vertices) {
            int i = 0;
            for (Component e : v.connections) {
                int eTerm = e.connections.indexOf(v);

                if (eTerm == -1) {
                    graph.addEdge("err[" + id + "]", e.getUID(), e.getUID());
                    id++;
                    i++;
                    continue;
                }

                String c1 = v.getUID();
                String c1t = v.terminals.get(i);
                String comp = v.subComponents.get(i);
                String c2t = e.terminals.get(eTerm);
                String c2 = e.getUID();

                //graph.addEdge(c1 + "." + c1t + "-" + comp + "-" + c2 + "." + c2t, v.getUID(), e.getUID());
                graph.addEdge(comp + "[" + id + "]", v.getUID(), e.getUID());
                id++;
                i++;
            }
        }

        Layout<Integer, String> layout = new KKLayout(graph);//new FRLayout(graph);
        layout.setSize(new Dimension(1300, 1300));
        VisualizationViewer<Integer, String> vv = new VisualizationViewer<>(layout);
        vv.setPreferredSize(new Dimension(350, 350));
        vv.getRenderContext().setVertexLabelTransformer(new ToStringLabeller());
        vv.getRenderContext().setEdgeLabelTransformer(new ToStringLabeller() {
            @Override
            public String transform(Object v) {
                String s = v.toString();
                return s.substring(0, s.indexOf('['));
            }
        });
        DefaultModalGraphMouse gm = new DefaultModalGraphMouse();
        gm.setMode(ModalGraphMouse.Mode.TRANSFORMING);
        vv.setGraphMouse(gm);
        JFrame frame = new JFrame("Interactive Graph 2D View");
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.getContentPane().add(vv);
        frame.pack();
        frame.setVisible(true);

    }

    public void show3D() {

        // instanciando um painel de desenho de 350px x 350px
        DrawingPanel3D drawingPanel = new DrawingPanel3D(350, 350);
        // criando e exibindo a janela principal
        drawingPanel.createFrame("Interactive Graph 3D View");
        // incluindo um objeto 3D (no caso as setas RGB no ponto [0,0,0])
        drawingPanel.append(new Axis());
        drawingPanel.append(new QuickGraph() {
            @Override
            public void draw(PGraphics g2d) {
                int unc = 0;
                for (Component c : vertices) {
                    unc += (c.pos == null) ? 1 : 0;
                }
                g2d.clear();
                g2d.fill(0);
                g2d.textSize(15);
                g2d.textAlign = PApplet.RIGHT;
                g2d.text("nodes: " + vertices.size() + " unc: " + unc, g2d.width - 30, g2d.height - 40);
            }

            @Override
            public void draw(PGraphics3D g3d) {
                for (Component c : vertices) {
                    if (c.pos != null) {

                        g3d.stroke(0);

                        if (c.type != null) {
                            g3d.fill(c.type.hashCode() * 2);
                        } else {
                            g3d.fill(1f, 1, (c.uid * 2 / 360f));
                        }

                        for (Component v : c.connections) {
                            if (v.pos == null) {
                                g3d.stroke(0, 255, 0);
                                break;
                            }
                        }

                        for (boolean b : c.doneConnections) {
                            if (!b) {
                                g3d.fill(255, 0, 0);
                                break;
                            }
                        }

                        g3d.pushMatrix();
                        g3d.translate(c.pos[0], c.pos[1], c.pos[2]);
                        g3d.box((c.joint && (c.name == null || (c.name != null && c.name.contains("ex")))) ? .03f : .20f);
                        g3d.fill(0);
                        g3d.scale(DrawingPanel3D.RESET_SCALE / 10);
                        g3d.textSize(100);
                        g3d.text(c.getUID() + Arrays.toString(c.pos), 120, 0, 0);
                        g3d.popMatrix();

                        g3d.stroke(0);

                        int i = 0;
                        for (Component v : c.connections) {
                            if (!c.doneConnections.get(i)) {
                                g3d.stroke(255, 0, 0);
                            }
                            if (v.pos != null) {
                                g3d.line(c.pos[0], c.pos[1], c.pos[2], v.pos[0], v.pos[1], v.pos[2]);
                            }
                            i++;
                        }
                    }
                }
            }
        });
    }
}
