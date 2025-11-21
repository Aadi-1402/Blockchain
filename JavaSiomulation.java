import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

// Simple simulation to demonstrate decentralized prevention of double-spend
// - UTXO model
// - Nodes validate, maintain local UTXO set + mempool
// - Miner packs mempool txs into a block and does trivial PoW (find hash with prefix zeros)
// - Conflicting txs racing: only one can get into the chain

public class SimpleNetworkSim {

    // ---- Data structures ----
    static class TXIn { String prevTxId; int prevIndex; String owner; }
    static class TXOut { String owner; int amount; }
    static class Transaction {
        String id;
        List<TXIn> inputs = new ArrayList<>();
        List<TXOut> outputs = new ArrayList<>();
        Transaction() { id = UUID.randomUUID().toString().substring(0,8); }
        public String toString() { return "TX("+id+")"; }
    }

    static class UTXOKey { String txId; int index;
        UTXOKey(String t,int i){txId=t;index=i;}
        public boolean equals(Object o){ if(!(o instanceof UTXOKey)) return false; UTXOKey k=(UTXOKey)o; return k.txId.equals(txId)&&k.index==index;}
        public int hashCode(){ return Objects.hash(txId,index); }
        public String toString(){ return txId+":"+index; }
    }

    static class Block {
        String prevHash; long nonce; String hash; List<Transaction> txs = new ArrayList<>();
        Block(String prev){ this.prevHash=prev; nonce=0; recalc(); }
        void recalc(){ hash = sha256(prevHash + nonce + txs.toString()); }
        public String toString(){ return "Block("+hash.substring(0,6)+")"; }
    }

    // Simple chain
    static class Chain {
        List<Block> blocks = new ArrayList<>();
        Chain(Block genesis){ blocks.add(genesis); }
        String tipHash(){ return blocks.get(blocks.size()-1).hash; }
    }

    // ---- Node ----
    static class Node {
        String name;
        Map<UTXOKey, TXOut> utxo = new ConcurrentHashMap<>();
        Map<String, Transaction> mempool = new ConcurrentHashMap<>();
        Chain chain;
        List<Node> peers = new ArrayList<>();

        Node(String name, Chain chain){ this.name=name; this.chain=chain; }

        void connect(Node other){ if(!peers.contains(other)) peers.add(other); }

        // Receive transaction from user or peers
        synchronized void receiveTx(Transaction tx) {
            if (validateTx(tx)) {
                mempool.put(tx.id, tx);
                // broadcast to peers
                for (Node p: peers) p.receiveTx(tx);
                System.out.println("[" + name + "] accepted " + tx);
            } else {
                System.out.println("[" + name + "] rejected " + tx + " (invalid or double-spend)");
            }
        }

        boolean validateTx(Transaction tx) {
            // check inputs exist in UTXO and not consumed by local mempool conflicts
            for (TXIn in: tx.inputs) {
                UTXOKey k = new UTXOKey(in.prevTxId, in.prevIndex);
                if (!utxo.containsKey(k)) return false;
            }
            // check not conflicting with existing mempool txs (simple: if a mempool tx consumes same UTXO reject)
            for (Transaction m: mempool.values()) {
                for (TXIn inM : m.inputs){
                    for (TXIn inT : tx.inputs){
                        if (inM.prevTxId.equals(inT.prevTxId) && inM.prevIndex == inT.prevIndex) return false;
                    }
                }
            }
            // sum check skipped for simplicity, assume amounts fit
            return true;
        }

        // Apply block atomically
        synchronized boolean receiveBlock(Block b) {
            // validate block header PoW
            if (!isValidPoW(b)) {
                System.out.println("[" + name + "] rejected block (bad PoW)");
                return false;
            }
            // validate all txs against current UTXO
            Set<UTXOKey> consumption = new HashSet<>();
            for (Transaction tx : b.txs) {
                for (TXIn in : tx.inputs) {
                    UTXOKey k = new UTXOKey(in.prevTxId, in.prevIndex);
                    if (!utxo.containsKey(k) || consumption.contains(k)) {
                        System.out.println("[" + name + "] rejected block (tx consumes missing or duplicate utxo) - " + tx);
                        return false;
                    }
                    consumption.add(k);
                }
            }
            // apply: remove spent UTXOs, add outputs
            for (Transaction tx : b.txs) {
                for (TXIn in : tx.inputs) utxo.remove(new UTXOKey(in.prevTxId,in.prevIndex));
                for (int i = 0; i < tx.outputs.size(); i++) {
                    TXOut out = tx.outputs.get(i);
                    utxo.put(new UTXOKey(tx.id,i), out);
                }
                mempool.remove(tx.id); // remove if present
            }
            // accept block into chain
            chain.blocks.add(b);
            // broadcast block to peers
            for (Node p: peers) p.receiveBlockIfNew(b);
            System.out.println("[" + name + "] accepted block " + b + " with txs " + b.txs);
            return true;
        }

        // helper to avoid infinite recursion when peer already processed block
        synchronized void receiveBlockIfNew(Block b) {
            if (chain.blocks.stream().anyMatch(x->x.hash.equals(b.hash))) return;
            receiveBlock(b);
        }
    }

    // ---- Miner (as separate actor) ----
    static class Miner implements Runnable {
        String name; Node node; int difficulty; volatile boolean stop=false;
        Miner(String name, Node node, int difficulty){ this.name=name; this.node=node; this.difficulty=difficulty; }
        public void run() {
            try {
                while(!stop) {
                    // gather mempool transactions (avoid conflicts)
                    List<Transaction> pool = new ArrayList<>(node.mempool.values());
                    // select non-conflicting
                    List<Transaction> selected = selectNonConflicting(pool);
                    Block b = new Block(node.chain.tipHash());
                    b.txs.addAll(selected);
                    // simple PoW: find hash starting with difficulty zeros
                    while (!isValidPoWLocal(b, difficulty) && !stop) {
                        b.nonce++;
                        b.recalc();
                        // tiny sleep to avoid busy-loop hogging
                        if (b.nonce % 10000 == 0) Thread.yield();
                    }
                    if (stop) break;
                    System.out.println("[" + name + "] mined block " + b + " with txs " + b.txs);
                    // broadcast block to node's network (simulate)
                    node.receiveBlock(b);
                    // wait a bit before next mining round
                    Thread.sleep(200);
                }
            } catch (Exception e){ e.printStackTrace(); }
        }
        private List<Transaction> selectNonConflicting(List<Transaction> pool) {
            List<Transaction> out = new ArrayList<>();
            Set<UTXOKey> used = new HashSet<>();
            for (Transaction tx : pool) {
                boolean ok = true;
                for (TXIn in : tx.inputs) {
                    UTXOKey k = new UTXOKey(in.prevTxId, in.prevIndex);
                    if (used.contains(k)) { ok = false; break; }
                    used.add(k);
                }
                if (ok) out.add(tx);
            }
            return out;
        }
        private boolean isValidPoWLocal(Block b, int diff) {
            String prefix = new String(new char[diff]).replace('\0','0');
            return b.hash.startsWith(prefix);
        }
    }

    // ---- Utilities ----
    static String sha256(String data) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            byte[] bytes = d.digest(data.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte by : bytes) {
                sb.append(String.format("%02x", by));
            }
            return sb.toString();
        } catch (Exception e) { throw new RuntimeException(e); }
    }
    static boolean isValidPoW(Block b) {
        String prefix = "00"; // nodes use difficulty 2 zeros
        return b.hash.startsWith(prefix);
    }

    // ---- Simulation ----
    public static void main(String[] args) throws Exception {
        // create genesis block and chain
        Block genesis = new Block("0");
        genesis.txs.clear();
        genesis.recalc();
        Chain chain1 = new Chain(genesis);

        // create network nodes
        Node A = new Node("A", chain1);
        Node B = new Node("B", chain1);
        Node C = new Node("C", chain1);

        // connect peers (fully connected for simplicity)
        A.connect(B); A.connect(C);
        B.connect(A); B.connect(C);
        C.connect(A); C.connect(B);

        // Give an initial UTXO to user "Alice": from genesis we pretend a tx id "genesis" index 0
        UTXOKey genesisUTXO = new UTXOKey("genesis", 0);
        TXOut out = new TXOut(); out.owner="Alice"; out.amount=100;
        A.utxo.put(genesisUTXO, out);
        B.utxo.put(genesisUTXO, out);
        C.utxo.put(genesisUTXO, out);

        // start a miner on node A
        Miner minerA = new Miner("MinerA", A, 2); // difficulty 2 zeros
        Thread minerThread = new Thread(minerA);
        minerThread.start();

        // Create two conflicting transactions that both try to spend the same genesis UTXO
        Transaction tx1 = new Transaction(); // Alice -> Bob
        TXIn in1 = new TXIn(); in1.prevTxId="genesis"; in1.prevIndex=0; in1.owner="Alice";
        tx1.inputs.add(in1);
        TXOut tx1out = new TXOut(); tx1out.owner="Bob"; tx1out.amount=100;
        tx1.outputs.add(tx1out);

        Transaction tx2 = new Transaction(); // Alice -> Mallory (double-spend attempt)
        TXIn in2 = new TXIn(); in2.prevTxId="genesis"; in2.prevIndex=0; in2.owner="Alice";
        tx2.inputs.add(in2);
        TXOut tx2out = new TXOut(); tx2out.owner="Mallory"; tx2out.amount=100;
        tx2.outputs.add(tx2out);

        System.out.println("\n--- Broadcasting conflicting txs from different entrypoints ---");
        // Broadcast tx1 to node B (propagates), and tx2 to node C (propagates) to simulate network race
        B.receiveTx(tx1);
        C.receiveTx(tx2);

        // let miner run for a short while to include whichever tx it sees first
        Thread.sleep(2000);

        // stop miner
        minerA.stop = true;
        minerThread.join();

        // Print final UTXO sets
        System.out.println("\n--- Final UTXO sets ---");
        System.out.println("Node A UTXO keys: " + A.utxo.keySet());
        System.out.println("Node B UTXO keys: " + B.utxo.keySet());
        System.out.println("Node C UTXO keys: " + C.utxo.keySet());

        // Show whether both txs ended up in chain
        System.out.println("\nChain blocks:");
        for (Block b : A.chain.blocks) {
            System.out.println(" - " + b + " txs:" + b.txs);
        }

        System.out.println("\nMempools:");
        System.out.println("A mempool: " + A.mempool.keySet());
        System.out.println("B mempool: " + B.mempool.keySet());
        System.out.println("C mempool: " + C.mempool.keySet());

        System.out.println("\nSimulation complete.");
    }
}
