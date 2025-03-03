package set.ex;

import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.concurrent.ArrayBlockingQueue;
import set.*;


/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {

    /**
     * state if cards were placed on the table
     * used to show hints 
     */
    private Boolean tableChange;

    /**
     * state if we are resetting the table after timeout/start
     */
    public volatile Boolean isFullLoop;

    /**
     * Queue of sets waiting to be tested.
     */
    private ArrayBlockingQueue<int[]> playerRequesQueue;

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;
    private final Player[] players;

    /**
     * The list of card ids that are left in the dealer's deck.
     */
    private final List<Integer> deck;

    /**
     * True if game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime = Long.MAX_VALUE;

    /**
     * Dealer constructor
     * @param env
     * @param table
     * @param players
     */
    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
        playerRequesQueue = new ArrayBlockingQueue<>(50);
        isFullLoop=true;
        tableChange=false;
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        for(Player player : players)
        {
            Thread playerThread = new Thread(player, "player"+player.id);
            playerThread.start();
        }
        while (!shouldFinish()) {
            placeCardsOnTable();
            timerLoop();
            updateTimerDisplay(false);
            removeAllCardsFromTable();
        }
        announceWinners();
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     */
    private void timerLoop() {
        while (!terminate && System.currentTimeMillis() < reshuffleTime) {
            sleepUntilWokenOrTimeout();
            updateTimerDisplay(false);
            removeCardsFromTable();
            placeCardsOnTable();
        }
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        for(Player player : players){//terminates all of the players first
            player.terminate();
            synchronized(player.getThread()){
                try {
                    player.getThread().notify();
                }
                catch (Exception e){}
            }
        }
        for(Player player : players){
            try {
                player.getThread().join();
            } catch (Exception e) {} 
        }
        terminate=true;
    }

    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true if the game should be finished.
     */
    private boolean shouldFinish() {
        return terminate || env.util.findSets(deck, 1).size() == 0;
    }

    /**
     * Checks cards should be removed from the table and removes them.
     */
    private void removeCardsFromTable() {
        boolean isSet = false;
        while(!playerRequesQueue.isEmpty()){
            int[] currSet = playerRequesQueue.poll();
            int currPlayerId= currSet[3];
            int[] checkingSet=new int[3]; 
            for(int i=0;i<3;i++)
            {
                checkingSet[i]=currSet[i];
            }
            if(isSetOnTable(currSet,currPlayerId)){
                isSet = env.util.testSet(checkingSet);
                if(isSet){
                    removePlayersInvalidTokens(checkingSet);
                    for(int i =0; i<3; i++){
                        env.ui.removeTokens(table.cardToSlot[currSet[i]]);
                        table.removeCard(table.cardToSlot[currSet[i]]);   
                    }
                    players[currPlayerId].pointOrPen(1);
                    updateTimerDisplay(true);
                }
                else{
                    players[currPlayerId].pointOrPen(-1);
                }
            }
            try{
                synchronized(players[currPlayerId].getThread()){
                    players[currPlayerId].getThread().notify();
                }
            }
            catch(Exception e){}
       }

       // env.util.testSet();
    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {
        if(!deck.isEmpty() && (table.countCards())<12){   
            for(int i=0 ;i< table.slotToCard.length;i++)
            {
                if(table.slotToCard[i]==null&&!deck.isEmpty()){
                    table.placeCard(drawCard(), i);
                    tableChange=true;
                }
            }
            reshuffleTime=System.currentTimeMillis()+env.config.turnTimeoutMillis;
            env.ui.setCountdown(reshuffleTime-System.currentTimeMillis(), false);
        }
        if(isFullLoop){isFullLoop=false;}
        if(env.config.hints&&tableChange){
            table.hints();
            tableChange=false;
        }
        for(Player player : players){//wake all players
            synchronized(player){
                player.notify();
            }
        }
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    private void sleepUntilWokenOrTimeout() {
        long tempTime =reshuffleTime- System.currentTimeMillis();
        if(tempTime<=env.config.endGamePauseMillies){
            synchronized(this){
                try{this.wait(10);}
                catch(Exception e){}
            }
        }
        else{
            synchronized(this){
                try{this.wait(1000);}
                catch(Exception e){}
            }
        }
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        long tempTime =reshuffleTime- System.currentTimeMillis();
        if(!reset && tempTime>0){
            if(tempTime<=19){
                env.ui.setCountdown(0, true);
            }
            else if(tempTime<=env.config.turnTimeoutWarningMillis){
                env.ui.setCountdown(tempTime, true);
            }
            else{
                env.ui.setCountdown(tempTime, false);
            }
        }
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
        isFullLoop=true;
        env.ui.removeTokens();
        for (int i =0 ; i < table.slotToCard.length ; i++){
            Integer currCard = table.slotToCard[i];
            if (currCard != null){
                deck.add(currCard);
                table.removeCard(table.cardToSlot[currCard]);
            }
        }
        for(Player p : players){
            p.removeAllCardsFromQueue();
        }
        if(env.util.findSets(deck, 1).size()<1){
            terminate();
        }
    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
        int maxPoints =-1;

        for(int i = 0;i<players.length ; i++){
            int currPlayerScore = players[i].score();
            if(currPlayerScore>maxPoints){
                maxPoints = currPlayerScore;
            }
        }
        int size = 0;
        for(int i = 0;i<players.length ; i++){
            if(players[i].score() == maxPoints){
                size++;
            }
        }
        //list of all winners
        int[] winnersOut = new int[size];
        int tempIndex=0;
        for(int i = 0;i<players.length ; i++){
            if(players[i].score() == maxPoints){
                winnersOut[tempIndex] = (players[i].id);
                tempIndex++;
            }
        }
        //change UI
        env.ui.announceWinner(winnersOut);
    }

    /**
     * returns if this set is currently on the table
     * @param currSet
     * @param currPlayerId
     * @return
     */
    private Boolean isSetOnTable(int[] currSet,int currPlayerId){
        boolean onTable = true;
        for(int i=0;i<3;i++)
        {
            if(table.isCardOnTable(currSet[i])==false)
            {
                onTable = false;
                players[currPlayerId].addOrRemoveCard(currSet[i], true);
            }
        }
        return onTable;
    }

     /**
      * draws a random card from the dack
     * @return random card
     */
    private int drawCard(){
        Random random = new Random();
        int randomNum = random.nextInt(deck.size());
        return deck.remove(randomNum);
    }

    /**
     * adds a set for testing to the dealer's queue
     * @param playerSet player's set info
     */
    public synchronized void addSetForTest(int[] playerSet){
        //adds the player's set to the queue
            playerRequesQueue.add(playerSet);
    }

    /**
     * removes all player's invalid tokens 
     * @param set tokens to be removed
     */
    private void removePlayersInvalidTokens(int[]set){
        for(Player player:players)
        {
            for(int i=0;i<set.length;i++)
            {
                player.addOrRemoveCard(set[i], true);
            }
        }
    }


    // FOR TESTING PURPOSES ONLY~!!!!
    /**
     * a function specifically for testing
     * getter for dealer's queue
     * @return dealer's request queue
     */
    public ArrayBlockingQueue<int[]> getDealersQueueForTest(){
        return this.playerRequesQueue;
    }
}