package set.ex;
import set.Env;


import java.util.Random;
import java.util.Iterator;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * This class manages the players' threads and data
 *
 * @inv id >= 0
 * @inv score >= 0
 */
public class Player implements Runnable {

    /**
     * the set we will send the dealer for testing
     */
    private int[] mySet;

    /**
     * dealer's answer about our set
     */
    private int foundSet;

    /**
     * do we disable the keypressed function
     */
    private volatile Boolean isKeyFrozen;

    /**
     * is this player ready for dealer's test
     */
    private Boolean readyForCheckSet;

    /**
     *queue of our chosen cards
     */
    private ArrayBlockingQueue<Integer>tokenQueue;

    /**
     *this player's dealer
     */
    private final Dealer myDealer;

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;

    /**
     * The id of the player (starting from 0).
     */
    public final int id;

    /**
     * The thread representing the current player.
     */
    private Thread playerThread;

    /**
     * The thread of the AI (computer) player (an additional thread used to generate key presses).
     */
    private Thread aiThread;

    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The current score of the player.
     */
    private int score;

    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided manually, via the keyboard).
     */
    public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
        this.env = env;
        this.table = table;
        this.id = id;
        this.human = human;
        this.foundSet= 0;
        this.isKeyFrozen=false;
        this.readyForCheckSet=false;
        this.tokenQueue=new ArrayBlockingQueue<>(3);
        this.mySet= new int[4];
        mySet[3]=id;
        this.myDealer = dealer;
    }

    /**
     * The main player thread of each player starts here (main loop for the player thread).
     */
    @Override
    public void run() {
        playerThread = Thread.currentThread();
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        if (!human) createArtificialIntelligence();
        while (!terminate) {
            if(readyForCheckSet){
                updateMySet();
                myDealer.addSetForTest(mySet);
                synchronized(myDealer){try {myDealer.notify();} catch (Exception e) {}}
                readyForCheckSet=false;
                synchronized(playerThread){try {playerThread.wait();} catch (Exception e) {}}
                if(foundSet==1){point();}
                else if(foundSet==-1){penalty();}
                
            }
            else{
                isKeyFrozen=false;
                synchronized(playerThread){try {playerThread.wait();} catch (Exception e) {}}
            }

        }
        if (!human) try { aiThread.join(); } catch (InterruptedException ignored) {}
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very, very smart AI (!)
        aiThread = new Thread(() -> {
            env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
            Random random =new Random();
            while (!terminate) {
                try {
                    synchronized (this) {wait();}
                } catch (InterruptedException ignored) {}

                while(!isKeyFrozen&&!myDealer.isFullLoop){
                    try{Thread.sleep(10);}catch(Exception e){}
                    int ranNum = random.nextInt(env.config.rows*env.config.columns);
                    keyPressed(ranNum);
                }
                
            }
            env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id);
        aiThread.start();
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        terminate=true;
        try {aiThread.interrupt();} catch (Exception e) {}
    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public synchronized void keyPressed(int slot) {
        if(!isKeyFrozen&&!myDealer.isFullLoop){
            if(table.slotToCard[slot]!=null){
                int currCard = table.slotToCard[slot];
                if(tokenQueue.size()==2){
                    addOrRemoveCard(currCard,false);
                    if(tokenQueue.size()==3){
                        readyForCheckSet=true;
                        isKeyFrozen=true;
                        synchronized(playerThread){playerThread.notify();}
                    }
                }
                else {
                    addOrRemoveCard(currCard,false);
                }
            }
        }
    }

    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point() {
        int ignored = table.countCards(); // this part is just for demonstration in the unit tests
        env.ui.setScore(id, ++score);
        long freezeTimer=env.config.pointFreezeMillis;
        while(freezeTimer>0)
        {
            env.ui.setFreeze(id, freezeTimer);//show freeze timer
            freezeTimer-=1000;
            try {
                Thread.sleep(1000);
            } catch (Exception e) {}
        }
        env.ui.setFreeze(id,0);
        foundSet=0;
    }

    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {
        long freezeTimer=env.config.penaltyFreezeMillis;
        if(freezeTimer>0)
        {
            while(freezeTimer>0)
            {
                env.ui.setFreeze(id, freezeTimer);//show freeze timer
                freezeTimer-=1000;
                try {
                    Thread.sleep(1000);
                } catch (Exception e) {}
            }
            env.ui.setFreeze(id,0);
        }
        foundSet=0;
    }


    /**
     * @return player's score
     */
    public int score() {
        return score;
    }

    /**
     * 1 -if the player recieves a point
     * 0 player's set was removed
     * -1 if the player needs a penalty
     * @param i
     */
    public void pointOrPen(int i)
    {
        foundSet=i;
    }

    /**
     * returns the player's thread
     * @return player's thread
     */
    public Thread getThread()
    {
        return playerThread;
    }

    
    /**
     * adds and removes cards from our card queue
     * will only remove if flag "justRemove" is true
     * @param card value of card
     * @param justRemove true if we the function is called just for removal
     */
    public synchronized void addOrRemoveCard(int card,boolean justRemove)
    {
        if(tokenQueue.contains(card))
        {
            tokenQueue.remove(card);
            if(table.cardToSlot[card]!=null)
                env.ui.removeToken(id, table.cardToSlot[card]);
        }
        else if(!justRemove)
        {
            if(tokenQueue.size()<3){
                tokenQueue.add(card);
                env.ui.placeToken(id, table.cardToSlot[card]);
            }
        }
    }


    /**
     * updates mySet to the set chosen by the player to be tested
     */
    private void updateMySet(){
        Iterator<Integer> tokenQueueIter = tokenQueue.iterator();
        int i=0;
        while(tokenQueueIter.hasNext()){
            mySet[i]=tokenQueueIter.next();
            i++;
        }
    }
    public void removeAllCardsFromQueue()
    {
        tokenQueue.clear();
    }


    //THIS IS FOR TESTING ONLY~!!!!!
    /**
     * THIS IS FOR TESTING ONLY
     * @return terminate state
     */
    public Boolean getPlayersTerminateForTest()
    {
        return this.terminate;
    }

}
