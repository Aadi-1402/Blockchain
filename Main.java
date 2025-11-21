public class Main {
    public static void main(String[] args) {

        Blockchain.chain.add(new Block("Genesis Block", "0"));  

        System.out.println("Adding Block 1...");
        Blockchain.addBlock(new Block("Block #1 Data", Blockchain.getLatestBlock().hash));

        System.out.println("Adding Block 2...");
        Blockchain.addBlock(new Block("Block #2 Data", Blockchain.getLatestBlock().hash));

        System.out.println("\nBlockchain is valid: " + Blockchain.isChainValid());

        // Print the full blockchain
        for (Block b : Blockchain.chain) {
            System.out.println("\nHash: " + b.hash);
            System.out.println("Prev: " + b.previousHash);
        }
    }
}
