package ch.logixisland.anuto.game;

import android.content.Context;
import android.os.Handler;
import android.util.Log;
import android.widget.Toast;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import ch.logixisland.anuto.game.data.EnemyDescriptor;
import ch.logixisland.anuto.game.data.Level;
import ch.logixisland.anuto.game.data.PlateauDescriptor;
import ch.logixisland.anuto.game.data.Settings;
import ch.logixisland.anuto.game.data.Wave;
import ch.logixisland.anuto.game.objects.Enemy;
import ch.logixisland.anuto.game.objects.GameObject;
import ch.logixisland.anuto.game.objects.Plateau;
import ch.logixisland.anuto.game.objects.Tower;
import ch.logixisland.anuto.util.container.ListenerList;
import ch.logixisland.anuto.util.math.MathUtils;

public class GameManager {

    /*
    ------ Constants ------
     */

    private final static String TAG = GameManager.class.getSimpleName();

    /*
    ------ Static ------
     */

    private static GameManager sInstance;

    public static GameManager getInstance() {
        if (sInstance == null) {
            sInstance = new GameManager();
        }

        return sInstance;
    }

    /*
    ------ Listener Interface ------
     */

    public interface Listener {

    }

    public interface OnGameStartedListener extends Listener {
        void onGameStarted();
    }

    public interface OnGameOverListener extends Listener {
        void onGameOver();
    }

    public interface OnWaveStartedListener extends Listener {
        void onWaveStarted(Wave wave);
    }

    public interface OnNextWaveReadyListener extends Listener {
        void onNextWaveReady(Wave wave);
    }

    public interface OnWaveDoneListener extends Listener {
        void onWaveDone(Wave wave);
    }

    public interface OnCreditsChangedListener extends Listener {
        void onCreditsChanged(int credits);
    }

    public interface OnBonusChangedListener extends Listener {
        void onBonusChanged(int bonus, int earlyBonus);
    }

    public interface OnLivesChangedListener extends Listener {
        void onLivesChanged(int lives);
    }

    public interface OnShowTowerInfoListener extends Listener {
        void onShowTowerInfo(Tower tower);
    }

    public interface OnHideTowerInfoListener extends Listener {
        void onHideTowerInfo();
    }

    public interface OnTowersAgedListener extends Listener {
        void onTowersAged();
    }

    /*
    ------ WaveManager Class ------
     */

    private class WaveManager implements GameObject.Listener {

        private Wave mWave;
        private int mExtend;
        private Handler mWaveHandler;

        private boolean mAborted;
        private boolean mNextWaveReady;

        private int mEnemiesInQueue;
        private List<Enemy> mEnemiesInGame = new CopyOnWriteArrayList<>();

        private volatile int mWaveReward;
        private volatile float mHealthModifier;
        private volatile float mRewardModifier;

        private int mEarlyBonus;

        public WaveManager(Wave wave, int extend) {
            mWave = wave;
            mExtend = extend;
            mWaveHandler = mGame.createHandler();

            mWaveReward = mWave.waveReward;
            mHealthModifier = mWave.healthModifier;
            mRewardModifier = mWave.rewardModifier;
        }

        public void start() {
            mWaveHandler.post(new Runnable() {
                @Override
                public void run() {
                    int delay = 0;
                    float offsetX = 0f;
                    float offsetY = 0f;

                    mEnemiesInQueue = mWave.enemies.size() * (mExtend + 1);
                    mActiveWaves.add(WaveManager.this);

                    for (int i = 0; i < mExtend + 1; i++) {
                        for (EnemyDescriptor d : mWave.enemies) {
                            if (MathUtils.equals(d.delay, 0f, 0.1f)) {
                                offsetX += d.offsetX;
                                offsetY += d.offsetY;
                            } else {
                                offsetX = d.offsetX;
                                offsetY = d.offsetY;
                            }

                            final Enemy e = d.create();
                            e.addListener(WaveManager.this);
                            e.modifyHealth(mHealthModifier);
                            e.modifyReward(mRewardModifier);
                            e.setPath(mLevel.getPaths().get(d.pathIndex));
                            e.move(offsetX, offsetY);

                            if (i > 0 || mWave.enemies.indexOf(d) > 0) {
                                delay += (int) (d.delay * 1000f);
                            }

                            mEarlyBonus += e.getReward();

                            mWaveHandler.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    mGame.add(e);
                                }
                            }, delay);
                        }
                    }

                    onWaveStarted(mWave);

                    calcEarlyBonus();
                }
            });
        }

        public void abort() {
            mWaveHandler.post(new Runnable() {
                @Override
                public void run() {
                    mWaveHandler.removeCallbacksAndMessages(null);
                    mEnemiesInQueue = 0;
                    mAborted = true;
                }
            });
        }

        public void giveReward() {
            mWaveHandler.post(new Runnable() {
                @Override
                public void run() {
                    giveCredits(mWaveReward, true);
                    mWaveReward = 0;
                }
            });
        }

        @Override
        public void onObjectAdded(GameObject obj) {
            mEnemiesInQueue--;
            mEnemiesInGame.add((Enemy)obj);

            if (mEnemiesInQueue == 0 && hasNextWave() && !mAborted && !mNextWaveReady) {
                mNextWaveReady = true;
                onNextWaveReady();
            }
        }

        @Override
        public void onObjectRemoved(GameObject obj) {
            mEnemiesInGame.remove(obj);
            mEarlyBonus -= ((Enemy)obj).getReward();

            if (mEnemiesInQueue == 0 && mEnemiesInGame.isEmpty() && !mAborted) {
                mActiveWaves.remove(this);

                giveCredits(mWaveReward, true);
                mWaveReward = 0;

                ageTowers();
                onWaveDone(mWave);

                if (!hasNextWave()) {
                    mGameOver = true;
                    mGameWon = true;
                    onGameOver();
                }
            }

            calcEarlyBonus();
        }
    }

    /*
    ------ Members ------
     */

    private GameEngine mGame;
    private Level mLevel;
    private Tower mSelectedTower;
    private Context mContext;

    private int mNextWaveIndex;

    private volatile int mCredits;
    private volatile int mCreditsEarned;
    private volatile int mLives;
    private volatile int mEarlyBonus;
    private volatile boolean mGameOver;
    private volatile boolean mGameWon;

    private List<WaveManager> mActiveWaves = new CopyOnWriteArrayList<>();

    private ListenerList<Listener> mListeners = new ListenerList<>();

    /*
    ------ Constructors ------
     */

    public GameManager() {
        mGame = GameEngine.getInstance();
        mGameOver = true;
    }

    /*
    ------ Methods ------
     */

    public void setContext(Context context) {
        mContext = context;
    }

    public Context getContext(Context context) {
        return mContext;
    }


    private void reset() {
        for (WaveManager m : mActiveWaves) {
            m.abort();
        }

        mActiveWaves.clear();
        mGame.clear();

        mSelectedTower = null;
        mNextWaveIndex = 0;
        mGameOver = false;

        hideTowerInfo();
    }

    public void restart() {
        reset();

        for (PlateauDescriptor d : mLevel.getPlateaus()) {
            Plateau p = d.create();
            p.setPosition(d.x, d.y);
            mGame.add(p);
        }

        Settings settings = mLevel.getSettings();
        mGame.setGameSize(settings.width, settings.height);

        onGameStarted();

        setCredits(settings.credits);
        setLives(settings.lives);

        mEarlyBonus = 0;
        mCreditsEarned = settings.credits;

        onBonusChanged();
    }


    public Level getLevel() {
        return mLevel;
    }

    public void setLevel(Level level) {
        mLevel = level;

        if (mLevel == null) {
            reset();
        } else {
            restart();
        }
    }


    public int getWaveNumber() {
        return mNextWaveIndex;
    }

    public boolean hasCurrentWave() {
        return !mActiveWaves.isEmpty();
    }

    public boolean hasNextWave() {
        if (mLevel.getSettings().endless && !mLevel.getWaves().isEmpty()) {
            return true;
        }

        return mNextWaveIndex < mLevel.getWaves().size();
    }

    public Wave getCurrentWave() {
        if (!hasCurrentWave()) {
            return null;
        }

        return getCurrentWaveManager().mWave;
    }

    private WaveManager getCurrentWaveManager() {
        if (!hasCurrentWave()) {
            return null;
        }

        return mActiveWaves.get(mActiveWaves.size() - 1);
    }

    public Wave getNextWave() {
        if (!hasNextWave()) {
            return null;
        }

        return mLevel.getWaves().get(mNextWaveIndex % mLevel.getWaves().size());
    }

    public int getWaveIterationCount() {
        return mNextWaveIndex / mLevel.getWaves().size();
    }

    public void startNextWave() {
        if (hasCurrentWave()) {
            getCurrentWaveManager().giveReward();
            giveCredits(mEarlyBonus, false);
        }

        Wave nextWave = getNextWave();
        int extend = nextWave.extend * getWaveIterationCount();

        WaveManager m = new WaveManager(nextWave, extend);

        if (mLevel.getSettings().endless) {
            calcWaveModifiers(m);
        }

        m.start();

        mNextWaveIndex++;
    }


    public int getCredits() {
        return mCredits;
    }

    public void setCredits(int credits) {
        mCredits = credits;
        onCreditsChanged();
    }

    public void giveCredits(int amount, boolean earned) {
        if (amount <= 0) {
            return;
        }

        mCredits += amount;

        if (earned) {
            mCreditsEarned += amount;
        }

        onCreditsChanged();
    }

    public void takeCredits(int amount) {
        if (amount <= 0) {
            return;
        }

        mCredits -= amount;
        onCreditsChanged();
    }

    public int getBonus() {
        if (!hasCurrentWave()) {
            return 0;
        }

        return getCurrentWaveManager().mWaveReward;
    }

    public int getEarlyBonus() {
        return mEarlyBonus;
    }


    public int getLives() {
        return mLives;
    }

    public void setLives(int lives) {
        mLives = lives;
        onLivesChanged();
    }

    public void giveLives(int count) {
        if (!isGameOver()) {
            mLives += count;
            onLivesChanged();
        }
    }

    public void takeLives(int count) {
        mLives -= count;

        onLivesChanged();

        if (mLives < 0 && !mGameOver) {
            mGameOver = true;
            mGameWon = false;
            onGameOver();
        }
    }


    public boolean isGameOver() {
        return mGameOver;
    }

    public boolean isGameWon() {
        return mGameWon;
    }


    public Tower getSelectedTower() {
        return mSelectedTower;
    }

    public void setSelectedTower(Tower tower) {
        if (mSelectedTower != null) {
            mSelectedTower.hideRange();
        }

        mSelectedTower = tower;

        if (mSelectedTower != null) {
            mSelectedTower.showRange();
        }
    }

    public void showTowerInfo(Tower tower) {
        onShowTowerInfo(tower);
    }

    public void hideTowerInfo() {
        onHideTowerInfo();
    }


    private void ageTowers() {
        Iterator<Tower> it = mGame.get(TypeIds.TOWER).cast(Tower.class);
        while (it.hasNext()) {
            Tower t = it.next();
            t.devalue(mLevel.getSettings().ageModifier);
        }

        onTowersAged();
    }

    private void calcEarlyBonus() {
        float earlyBonus = 0;

        for (WaveManager m : mActiveWaves) {
            earlyBonus += m.mEarlyBonus;
        }

        float modifier = mLevel.getSettings().earlyModifier;
        float root = mLevel.getSettings().earlyRoot;

        mEarlyBonus = Math.round(modifier * (float)Math.pow(earlyBonus, 1f / root));
        onBonusChanged();
    }

    private void calcWaveModifiers(WaveManager waveMan) {
        Log.d(TAG, String.format("calculating wave modifiers for wave %d...", getWaveNumber() + 1));
        Log.d(TAG, String.format("creditsEarned=%d", mCreditsEarned));

        float waveHealth = 0f;

        for (EnemyDescriptor d : waveMan.mWave.enemies) {
            waveHealth += mLevel.getEnemyConfig(d.clazz).health;
        }

        waveHealth *= waveMan.mExtend + 1;

        Log.d(TAG, String.format("waveHealth=%f", waveHealth));

        Settings settings = mLevel.getSettings();

        float damagePossible = settings.linearDifficulty * mCreditsEarned + settings.quadraticDifficulty * MathUtils.square(mCreditsEarned);
        float healthModifier = damagePossible / waveHealth;
        float rewardModifier = settings.rewardModifier * (float)Math.pow(healthModifier, 1f / settings.rewardRoot);

        if (rewardModifier < 1f) {
            rewardModifier = 1f;
        }

        Log.d(TAG, String.format("healthModifier=%f", healthModifier));
        Log.d(TAG, String.format("rewardModifier=%f", rewardModifier));

        waveMan.mHealthModifier *= healthModifier;
        waveMan.mRewardModifier *= rewardModifier;
        waveMan.mWaveReward *= (getWaveNumber() / mLevel.getWaves().size()) + 1;

        StringBuilder builder = new StringBuilder();
        builder.append(String.format("creditsEarned=%d\n", mCreditsEarned));
        builder.append(String.format("healthModifier=%f\n", healthModifier));
        builder.append(String.format("rewardModifier=%f", rewardModifier));
        Toast.makeText(mContext, builder.toString(), Toast.LENGTH_LONG).show();
    }

    /*
    ------ Listener Stuff ------
     */

    public void addListener(Listener listener) {
        mListeners.add(listener);
    }

    public void removeListener(Listener listener) {
        mListeners.remove(listener);
    }


    private void onGameStarted() {
        Log.i(TAG, "Game started.");

        for (OnGameStartedListener l : mListeners.get(OnGameStartedListener.class)) {
            l.onGameStarted();
        }
    }

    private void onGameOver() {
        Log.i(TAG, "Game over.");

        for (OnGameOverListener l : mListeners.get(OnGameOverListener.class)) {
            l.onGameOver();
        }
    }

    private void onCreditsChanged() {
        for (OnCreditsChangedListener l : mListeners.get(OnCreditsChangedListener.class)) {
            l.onCreditsChanged(getCredits());
        }
    }

    private void onBonusChanged() {
        for (OnBonusChangedListener l : mListeners.get(OnBonusChangedListener.class)) {
            l.onBonusChanged(getBonus(), getEarlyBonus());
        }
    }

    private void onLivesChanged() {
        for (OnLivesChangedListener l : mListeners.get(OnLivesChangedListener.class)) {
            l.onLivesChanged(getLives());
        }
    }

    public void onWaveStarted(Wave wave) {
        Log.i(TAG, "Wave started.");

        for (OnWaveStartedListener l : mListeners.get(OnWaveStartedListener.class)) {
            l.onWaveStarted(wave);
        }
    }

    private void onNextWaveReady() {
        Log.i(TAG, "Next wave ready.");

        for (OnNextWaveReadyListener l : mListeners.get(OnNextWaveReadyListener.class)) {
            l.onNextWaveReady(getNextWave());
        }
    }

    public void onWaveDone(Wave wave) {
        Log.i(TAG, "Wave done.");

        for (OnWaveDoneListener l : mListeners.get(OnWaveDoneListener.class)) {
            l.onWaveDone(wave);
        }
    }

    private void onShowTowerInfo(Tower tower) {
        for (OnShowTowerInfoListener l : mListeners.get(OnShowTowerInfoListener.class)) {
            l.onShowTowerInfo(tower);
        }
    }

    private void onHideTowerInfo() {
        for (OnHideTowerInfoListener l : mListeners.get(OnHideTowerInfoListener.class)) {
            l.onHideTowerInfo();
        }
    }

    private void onTowersAged() {
        for (OnTowersAgedListener l : mListeners.get(OnTowersAgedListener.class)) {
            l.onTowersAged();
        }
    }
}