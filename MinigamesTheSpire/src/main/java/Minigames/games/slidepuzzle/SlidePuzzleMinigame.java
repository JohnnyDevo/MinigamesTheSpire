package Minigames.games.slidepuzzle;

import Minigames.Minigames;
import Minigames.games.AbstractMinigame;
import Minigames.games.input.bindings.BindingGroup;
import basemod.ReflectionHacks;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureAtlas;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector2;
import com.esotericsoftware.spine.*;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.core.AbstractCreature;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.events.GenericEventDialog;
import com.megacrit.cardcrawl.helpers.FontHelper;
import com.megacrit.cardcrawl.localization.EventStrings;

import java.util.ArrayList;
import java.util.Collections;

public class SlidePuzzleMinigame extends AbstractMinigame {
    //localization
    public static final String ID = Minigames.makeID("SlidePuzzle");
    public static final EventStrings eventStrings = CardCrawlGame.languagePack.getEventString(ID);

    //board info
    public int BOARD_SIZE;
    public float RAW_TILE_SIZE;
    public float TILE_SIZE;
    public int TILE_COUNT;
    private Tile[][] board;
    private ArrayList<CreatureInfo> creatures;

    //fbo info
    private FrameBuffer monsterBuffer;
    public int CAMERA_SIZE;
    private OrthographicCamera camera;

    //controlled by tiles, no clicking during slides
    public boolean sliding = false;

    //game state control
    private GameState state;
    private float INITIALIZE_TIMER;
    private float GAME_TIMER;
    private float VICTORY_TIMER;
    private float DEFEAT_TIMER;
    private float duration;

    //timer display after game is over
    private int victoryTime = 0;

    //effect variables
    public Color victoryColor = null;
    private float VICTORY_RAINBOW_SPEED;
    private float colorTimer;

    //rewards calculation
    private int rewardsCount = 0;
    private int goldAmt;
    private AbstractCard.CardRarity rarity;

    @Override
    protected BindingGroup getBindings() {
        BindingGroup bindings = new BindingGroup();

        //if left click, click is on game board, no tiles are sliding, and it's during the game
        //find which tile was clicked on and tell it it was clicked
        bindings.addMouseBind((x, y, pointer) -> pointer == 0 && isOnBoard(x, y) && !sliding && state == GameState.PLAYING, (coordinate) -> {
            Vector2 gridPosition = convertToBoardPosition(coordinate, true);
            for (Tile[] row : board) {
                for (Tile tile : row) {
                    if (tile.gridPosition.equals(gridPosition)) {
                        tile.clicked(board);
                        return;
                    }
                }
            }
        });

        return bindings;
    }

    @Override
    public AbstractMinigame makeCopy() {
        return new SlidePuzzleMinigame();
    }

    @Override
    public void initialize() {
        super.initialize();

        //customizable variables
        BOARD_SIZE = 4;                         //width and height in # of tiles
        CAMERA_SIZE = 400;                      //width and height in pixels. Probably best to keep this always 400 because of manual creature placements
        INITIALIZE_TIMER = 5.0f;                //duration of starting animation
        GAME_TIMER = 90.0f;                     //duration of game
        VICTORY_TIMER = 10.0f;                  //duration of victory animation
        DEFEAT_TIMER = 10.0f;                   //duration of defeat animation
        VICTORY_RAINBOW_SPEED = 1.0f;           //how long it takes for one full rainbow in victory animation

        //derived constants
        RAW_TILE_SIZE = (float)CAMERA_SIZE / (float)BOARD_SIZE;
        TILE_SIZE = RAW_TILE_SIZE * Settings.scale;
        TILE_COUNT = (BOARD_SIZE * BOARD_SIZE) - 1;
        colorTimer = VICTORY_RAINBOW_SPEED;

        //fbo stuff
        monsterBuffer = new FrameBuffer(Pixmap.Format.RGBA8888, CAMERA_SIZE, CAMERA_SIZE, false, false);
        camera = new OrthographicCamera(CAMERA_SIZE, CAMERA_SIZE);

        //generate the board and its regions
        board = new Tile[BOARD_SIZE][BOARD_SIZE];
        for (int w = 0; w < BOARD_SIZE; ++w) {
            for (int h = 0; h < BOARD_SIZE; ++h) {
                board[w][h] = new Tile(new Vector2(w, h), this);
                float x = w * RAW_TILE_SIZE;
                float y = h * RAW_TILE_SIZE;
                TextureRegion region = new TextureRegion(monsterBuffer.getColorBufferTexture(), (int)x, (int)y, (int)RAW_TILE_SIZE, (int)RAW_TILE_SIZE);
                region.flip(false, true);
                board[w][h].region = region;
            }
        }
        board[BOARD_SIZE - 1][BOARD_SIZE - 1].isEmpty = true;

        //randomize which creatures appear in the board
        creatures = new ArrayList<>();
        BoardType boardType = BoardType.values()[AbstractDungeon.miscRng.random(BoardType.values().length - 1)];
        populateCreatureInfo(boardType);

        //start "starting game" animation
        state = GameState.INITIALIZING;
        duration = INITIALIZE_TIMER;

        //determine rewards
        switch (AbstractDungeon.actNum) {
            case 1:
                rarity = AbstractCard.CardRarity.COMMON;
                goldAmt = 5;
                break;
            case 2:
                rarity = AbstractCard.CardRarity.UNCOMMON;
                goldAmt = 10;
                break;
            default:
                rarity = AbstractCard.CardRarity.RARE;
                goldAmt = 15;
                break;
        }
    }

    @Override
    public void update(float elapsed) {
        super.update(elapsed);
        switch (state) {
            case INITIALIZING:
                updateInitialize(elapsed);
                break;
            case PLAYING:
                updatePlay(elapsed);
                break;
            case VICTORY:
                updateVictory(elapsed);
                break;
            case DEFEAT:
                updateDefeat(elapsed);
                break;
        }
    }

    void updateInitialize(float elapsed) {
        if (duration > (INITIALIZE_TIMER * 2.0f) / 3.0f) {
            //at the start of the animation, determine random spots for tiles to explode out to, but hold the image in place for user to see
            if (duration == INITIALIZE_TIMER) {
                for (Tile[] row : board) {
                    for (Tile tile : row) {
                        tile.setMovement(convertToBoardPosition(getRandomPoint(), false), INITIALIZE_TIMER / 3.0f);
                    }
                }
            }
            duration -= elapsed;
            return;
        } else {
            //update movement
            for (Tile[] row : board) {
                for (Tile tile : row) {
                    tile.updateInitialize(elapsed);
                }
            }
        }
        if (duration > INITIALIZE_TIMER / 3.0f) {
            duration -= elapsed;
            //once the tiles have reached the end points, shuffle their grid positions and set to move to grid
            if (duration <= INITIALIZE_TIMER / 3.0f) {
                shuffleTiles();
                for (Tile[] row : board) {
                    for (Tile tile : row) {
                        tile.setMovement(tile.gridPosition, INITIALIZE_TIMER / 3.0f);
                    }
                }
            }
        } else {
            duration -= elapsed;
        }
        //once finished, begin the game
        if (duration <= 0) {
            state = GameState.PLAYING;
            duration = GAME_TIMER;
        }
    }

    void updatePlay(float elapsed) {
        boolean success = true;
        //if any tile's current position does not match its initial position, the game is not yet over.
        for (Tile[] row : board) {
            for (Tile tile : row) {
                tile.updatePlay(elapsed);
                if (!tile.gridPosition.equals(tile.solvePosition)) {
                    success = false;
                }
            }
        }
        //update scoreboard
        tallyRewards();
        duration -= elapsed;
        if (duration <= 0.0f && !success) {
            state = GameState.DEFEAT;
            duration = DEFEAT_TIMER;
        }
        if (success && !sliding) {
            state = GameState.VICTORY;
            victoryTime = (int)Math.ceil(duration);     //lock in timer display
            duration = VICTORY_TIMER;
        }
    }

    void updateVictory(float elapsed) {
        if (victoryColor == null) {
            victoryColor = new Color(0, 0, 0, 1);
            tallyRewards();
        }
        //oscillate the render color for a nice little rainbow effect
        victoryColor.r = ((float)Math.sin(0d + (Math.PI * colorTimer * 2d)) * 0.5f) + 0.5f;
        victoryColor.g = ((float)Math.sin(((2d * Math.PI) / 3d) + (Math.PI * colorTimer * 2d)) * 0.5f) + 0.5f;
        victoryColor.b = ((float)Math.sin((((2d * Math.PI) / 3d) * 2d) + (Math.PI * colorTimer * 2d)) * 0.5f) + 0.5f;

        duration -= elapsed;
        colorTimer -= elapsed;
        if (duration <= 0.0f) {
            //complete game, indicate victory
        }
        if (colorTimer <= 0.0f) {
            colorTimer = VICTORY_RAINBOW_SPEED;
        }
    }

    void updateDefeat(float elapsed) {
        //turn all the borders and the timer black
        if (victoryColor == null) {
            victoryColor = new Color(0, 0, 0, 1);

            //set the tiles to explode to random spots
            for (Tile[] row : board) {
                for (Tile tile : row) {
                    tile.setMovement(convertToBoardPosition(getRandomPoint(), false), (DEFEAT_TIMER * 9.0f / 10.0f));
                }
            }

            //finalize the scoreboard
            tallyRewards();
        }

        //borders and timer fade out
        if (colorTimer >= 0) {
            victoryColor.a = colorTimer;
            colorTimer -= elapsed;
        } else {
            victoryColor.a = 0;
        }
        if (duration > (DEFEAT_TIMER * 9.0f / 10.0f)) {
            duration -= elapsed;
            return;
        }

        //move tiles over time
        for (Tile[] row : board) {
            for (Tile tile : row) {
                tile.updateDefeat(elapsed);
            }
        }
        duration -= elapsed;
        if (duration <= 0.0f) {
            //complete game, tally score
        }
    }

    private void tallyRewards() {
        rewardsCount = 0;
        //every tile currently in its original position is worth one reward
        for (Tile[] row : board) {
            for (Tile tile : row) {
                if (tile.gridPosition.equals(tile.solvePosition) && !tile.isEmpty) {
                    ++rewardsCount;
                }
            }
        }
    }

    @Override
    public void render(SpriteBatch sb) {
        super.render(sb);
        sb.end();
        monsterBuffer.begin();
        Gdx.gl.glClearColor(0.0F, 0.0F, 0.0F, 0.0F);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
        Gdx.gl.glColorMask(true,true,true,true);

        //the camera is purposefully still using the regular game camera to make the game background scale nicely into our fbo
        sb.begin();
        AbstractDungeon.scene.renderCombatRoomBg(sb);
        sb.end();
        sb.setColor(Color.WHITE.cpy());

        //set the skeleton renderer's camera to our camera so that our creatures don't look tiny
        Matrix4 tmp = CardCrawlGame.psb.getProjectionMatrix();
        CardCrawlGame.psb.setProjectionMatrix(camera.combined);

        //render all of our creatures
        CardCrawlGame.psb.begin();
        for (CreatureInfo creatureInfo : creatures) {
            creatureInfo.state.update(Gdx.graphics.getDeltaTime());
            creatureInfo.state.apply(creatureInfo.skeleton);
            creatureInfo.skeleton.updateWorldTransform();
            creatureInfo.skeleton.setPosition(creatureInfo.renderX, creatureInfo.renderY);
            AbstractCreature.sr.draw(CardCrawlGame.psb, creatureInfo.skeleton);
        }
        CardCrawlGame.psb.end();
        monsterBuffer.end();

        //restore the skeleton renderer's camera to its previous camera
        CardCrawlGame.psb.setProjectionMatrix(tmp);

        //render game border

        //render tiles
        sb.begin();
        for (Tile[] row : board) {
            for (Tile tile : row) {
                tile.render(sb);
            }
        }

        //render game timer
        int bgSize = ReflectionHacks.getPrivateInherited(this, SlidePuzzleMinigame.class, "BG_SIZE");
        float xOffset = -100.0f;
        float yOffset = 100.0f;
        if (state == GameState.PLAYING) {
            FontHelper.renderFontCentered(sb, FontHelper.energyNumFontPurple, String.valueOf((int)Math.ceil(duration)), (Settings.WIDTH / 2.0f) + (bgSize / 2.0f) + (xOffset * Settings.scale), (Settings.HEIGHT / 2.0f) + (yOffset * Settings.scale), Color.WHITE.cpy(), 3.0f * Settings.scale);
        }
        if (victoryColor != null) {
            FontHelper.renderFontCentered(sb, FontHelper.energyNumFontPurple, String.valueOf((int)Math.ceil(victoryTime)), (Settings.WIDTH / 2.0f) + (bgSize / 2.0f) + (xOffset * Settings.scale), (Settings.HEIGHT / 2.0f) + (yOffset * Settings.scale), victoryColor.cpy(), 3.0f * Settings.scale);
        }

        //render "scoreboard"
        String goldString = goldAmt + " gold";
        String potionString = rarity.toString().toLowerCase() + " potion";
        String cardString = rarity.toString().toLowerCase() + " card";
        String relicString = rarity.toString().toLowerCase() + " relic";

        for (int i = 1; i <= TILE_COUNT; ++i) {
            FontHelper.renderFontCentered(sb,
                    (rewardsCount >= i ? FontHelper.energyNumFontGreen : FontHelper.tipBodyFont),
                    (i == TILE_COUNT ? relicString : (i == (TILE_COUNT / 3 * 2) ? cardString: (i == (TILE_COUNT / 3) ? potionString : goldString))),
                    (Settings.WIDTH / 2.0f) - (bgSize / 2.0f) - (xOffset * Settings.scale),
                    (Settings.HEIGHT / 2.0f) - (bgSize / 3.0f) + ((((bgSize * 2.0f) / 3.0f) / TILE_COUNT) * i) - (20.0f * Settings.scale),
                    Color.WHITE.cpy(),
                    (rewardsCount >= i ? 0.75f : 1.0f) * Settings.scale);
        }
    }

    private void shuffleTiles() {
        //add every possible point to a single ArrayList
        ArrayList<Vector2> points = new ArrayList<>();
        for (int w = 0; w < BOARD_SIZE; ++w) {
            for (int h = 0; h < BOARD_SIZE; ++h) {
                points.add(new Vector2(w, h));
            }
        }
        //randomize the order of those points
        Collections.shuffle(points, AbstractDungeon.miscRng.random);

        //assign one of those points to each tile
        int emptyY = 0;
        for (Tile[] row : board) {
            for (Tile tile : row) {
                Vector2 point = points.get(0);
                points.remove(point);
                tile.gridPosition = point.cpy();
                if (tile.isEmpty) {
                    emptyY = (int)tile.gridPosition.y + 1;
                }
            }
        }

        //if the puzzle is not solvable, swap two tiles
        boolean solvable = isSolvable(emptyY);
        System.out.println("PUZZLE IS SOLVABLE: " + solvable);

        //I'm definitely programming my future swap puzzles differently
        if (!solvable) {
            System.out.println("Swapping two tiles");
            Tile tile1 = null;
            Tile tile2 = null;
            Tile tile3 = null;
            for (Tile[] row : board) {
                for (Tile tile : row) {
                    if (tile.gridPosition.equals(new Vector2(0,0))) {
                        tile1 = tile;
                    }
                    if (tile.gridPosition.equals(new Vector2(1, 0))) {
                        tile2 = tile;
                    }
                    if (tile.gridPosition.equals(new Vector2(2, 0))) {
                        tile3 = tile;
                    }
                }
            }
            if (tile1 == null || tile2 == null || tile3 == null) {
                System.out.println("this should literally be impossible");
                return;
            }
            if (tile1.isEmpty) {
                Vector2 tmp = tile2.gridPosition;
                tile2.gridPosition = tile3.gridPosition;
                tile3.gridPosition = tmp;
            } else if (tile2.isEmpty) {
                Vector2 tmp = tile1.gridPosition;
                tile1.gridPosition = tile3.gridPosition;
                tile3.gridPosition = tmp;
            } else {
                Vector2 tmp = tile1.gridPosition;
                tile1.gridPosition = tile2.gridPosition;
                tile2.gridPosition = tmp;
            }
        }
    }

    //slide puzzle math is complicated
    private int sumInversions() {
        int[] matrix = new int[BOARD_SIZE * BOARD_SIZE];
        int inversions = 0;
        for (Tile[] row : board) {
            for (Tile tile : row) {
                int solveValue = (int)tile.solvePosition.y * BOARD_SIZE + (int)tile.solvePosition.x;
                int gridValue = (int)tile.gridPosition.y * BOARD_SIZE + (int)tile.gridPosition.x;
                matrix[gridValue] = solveValue;
            }
        }
        for (int i = 0; i < matrix.length; ++i) {
            for (int j = i + 1; j < matrix.length; ++j) {
                if (matrix[i] > matrix[j] && matrix[i] != matrix.length - 1) {
                    ++inversions;
                }
            }
        }
        return inversions;
    }

    //the math is different depending on if the puzzle has an odd or even width
    private boolean isSolvable(int emptyRow) {
        if (BOARD_SIZE % 2 == 1) {
            return ((sumInversions() % 2 == 0));
        } else {
            return ((sumInversions() + BOARD_SIZE - emptyRow) % 2 == 0);
        }
    }

    //tests if a given coordinate is within the bounds of the tile board
    private boolean isOnBoard(float x, float y) {
        float gameSize = (BOARD_SIZE * TILE_SIZE) / 2.0f; //using tile size makes sure it's scaled
        return x >= (Settings.WIDTH / 2.0f) - gameSize &&
                x <= (Settings.WIDTH / 2.0f) + gameSize &&
                y >= (Settings.HEIGHT / 2.0f) - gameSize &&
                y <= (Settings.HEIGHT / 2.0f) + gameSize;
    }

    //converts a set of real game coordinates to tile coordinates.
    //if passed coordinates pass `isOnBoard`, x and y values will always be between 0 and BOARD_SIZE
    //floored returns true to make sure coordinate "snaps" to grid as an int
    private Vector2 convertToBoardPosition(Vector2 coordinate, boolean floored) {
        Vector2 retVal = coordinate.cpy();
        retVal.x -= Settings.WIDTH / 2.0f;
        retVal.y -= Settings.HEIGHT / 2.0f;
        retVal.x += (BOARD_SIZE * TILE_SIZE) / 2.0f;
        retVal.y += (BOARD_SIZE * TILE_SIZE) / 2.0f;
        if (floored) {
            retVal.x = (float) Math.floor(retVal.x / TILE_SIZE);
            retVal.y = (float) Math.floor(retVal.y / TILE_SIZE);
        } else {
            retVal.x /= TILE_SIZE;
            retVal.y /= TILE_SIZE;
        }
        return retVal;
    }

    //returns a random point in or reasonably near the game background
    private Vector2 getRandomPoint() {
        int bgSize = ReflectionHacks.getPrivateInherited(this, SlidePuzzleMinigame.class, "BG_SIZE");
        Vector2 retVal = new Vector2();
        retVal.x = AbstractDungeon.miscRng.random((Settings.WIDTH / 2.0f) - bgSize, (Settings.WIDTH / 2.0f) + bgSize);
        retVal.y = AbstractDungeon.miscRng.random((Settings.HEIGHT / 2.0f) - bgSize, (Settings.HEIGHT / 2.0f) + bgSize);
        return retVal;
    }

    @Override
    public void setupInstructionScreen(GenericEventDialog event) {
        event.updateBodyText(eventStrings.DESCRIPTIONS[0]);
        event.setDialogOption(eventStrings.OPTIONS[0]);
    }

    @Override
    public void dispose() {
        super.dispose();

        monsterBuffer.dispose();
    }

    @Override
    public void setupPostgameScreen(GenericEventDialog event) {
        event.updateBodyText(eventStrings.DESCRIPTIONS[1]);
        event.setDialogOption(eventStrings.DESCRIPTIONS[1]);
    }

    private enum BoardType {
        CULTIST,
        JAW_WORM,
        LOUSES,
        GREMLIN_NOB,
        LAGAVULIN,
        SLIME_BOSS,
        CENTURION_MYSTIC,
        SNECKO,
        THIEVES,
        TRI_SLAVER,
        GREMLIN_LEADER,
        CHAMP,
        DARKLINGS,
        ORBWALKER,
        TRANSIENT,
        SNAKE_LADY,
        NEMESIS,
        TIME_EATER
    }

    //these values all have to be fine-tuned manually, so it's probably best to keep a constant board size of 400
    private void populateCreatureInfo(BoardType type) {
        switch (type) {
            case CULTIST:
                creatures.add(new CreatureInfo(
                        "images/monsters/theBottom/cultist/skeleton.atlas",
                        "images/monsters/theBottom/cultist/skeleton.json",
                        0, "waving",
                        0, -150, 1.0f));
                break;
            case JAW_WORM:
                creatures.add(new CreatureInfo(
                        "images/monsters/theBottom/jawWorm/skeleton.atlas",
                        "images/monsters/theBottom/jawWorm/skeleton.json",
                        0, "idle",
                        -25, -125, 1.4f));
                break;
            case LOUSES:
                creatures.add(new CreatureInfo(
                        "images/monsters/theBottom/louseGreen/skeleton.atlas",
                        "images/monsters/theBottom/louseGreen/skeleton.json",
                        0, "idle",
                        -90, -50, 1.5f));
                creatures.add(new CreatureInfo(
                        "images/monsters/theBottom/louseRed/skeleton.atlas",
                        "images/monsters/theBottom/louseRed/skeleton.json",
                        0, "idle",
                        110, -150, 1.5f));
                break;
            case GREMLIN_NOB:
                creatures.add(new CreatureInfo(
                        "images/monsters/theBottom/nobGremlin/skeleton.atlas",
                        "images/monsters/theBottom/nobGremlin/skeleton.json",
                        0, "animation",
                        0, -170, 1.0f));
                break;
            case LAGAVULIN:
                creatures.add(new CreatureInfo(
                        "images/monsters/theBottom/lagavulin/skeleton.atlas",
                        "images/monsters/theBottom/lagavulin/skeleton.json",
                        0, "Idle_2",
                        0, -150, 1.0f));
                break;
            case SLIME_BOSS:
                creatures.add(new CreatureInfo(
                        "images/monsters/theBottom/boss/slime/skeleton.atlas",
                        "images/monsters/theBottom/boss/slime/skeleton.json",
                        0, "idle",
                        0, -150, 1.0f));
                break;
            case CENTURION_MYSTIC:
                creatures.add(new CreatureInfo(
                        "images/monsters/theCity/tank/skeleton.atlas",
                        "images/monsters/theCity/tank/skeleton.json",
                        0, "Idle",
                        -75, -150, 1.0f));
                creatures.add(new CreatureInfo(
                        "images/monsters/theCity/healer/skeleton.atlas",
                        "images/monsters/theCity/healer/skeleton.json",
                        0, "Idle",
                        125, -150, 1.0f));
                break;
            case SNECKO:
                creatures.add(new CreatureInfo(
                        "images/monsters/theCity/reptile/skeleton.atlas",
                        "images/monsters/theCity/reptile/skeleton.json",
                        0, "Idle",
                        40, -150, 1.2f));
                break;
            case THIEVES:
                creatures.add(new CreatureInfo(
                        "images/monsters/theCity/looterAlt/skeleton.atlas",
                        "images/monsters/theCity/looterAlt/skeleton.json",
                        0, "idle",
                        -75, -190, 1.0f));
                creatures.add(new CreatureInfo(
                        "images/monsters/theBottom/looter/skeleton.atlas",
                        "images/monsters/theBottom/looter/skeleton.json",
                        0, "idle",
                        125, -100, 1.0f));
                break;
            case TRI_SLAVER:
                creatures.add(new CreatureInfo(
                        "images/monsters/theCity/slaverMaster/skeleton.atlas",
                        "images/monsters/theCity/slaverMaster/skeleton.json",
                        0, "idle",
                        -90, -130, 1.0f));
                creatures.add(new CreatureInfo(
                        "images/monsters/theBottom/redSlaver/skeleton.atlas",
                        "images/monsters/theBottom/redSlaver/skeleton.json",
                        0, "idle",
                        60, -175, 1.0f));
                break;
            case GREMLIN_LEADER:
                creatures.add(new CreatureInfo(
                        "images/monsters/theBottom/femaleGremlin/skeleton.atlas",
                        "images/monsters/theBottom/femaleGremlin/skeleton.json",
                        0, "idle",
                        -145, -130, 1.0f));
                creatures.add(new CreatureInfo(
                        "images/monsters/theBottom/wizardGremlin/skeleton.atlas",
                        "images/monsters/theBottom/wizardGremlin/skeleton.json",
                        0, "animation",
                        -70, -150, 1.0f));
                creatures.add(new CreatureInfo(
                        "images/monsters/theCity/gremlinleader/skeleton.atlas",
                        "images/monsters/theCity/gremlinleader/skeleton.json",
                        0, "Idle",
                        100, -155, 1.0f));
                break;
            case CHAMP:
                creatures.add(new CreatureInfo(
                        "images/monsters/theCity/champ/skeleton.atlas",
                        "images/monsters/theCity/champ/skeleton.json",
                        0, "Idle",
                        0, -170, 1.0f));
                break;
            case DARKLINGS:
                creatures.add(new CreatureInfo(
                        "images/monsters/theForest/darkling/skeleton.atlas",
                        "images/monsters/theForest/darkling/skeleton.json",
                        0, "Idle",
                        0, -150, 1.4f));
                break;
            case ORBWALKER:
                creatures.add(new CreatureInfo(
                        "images/monsters/theForest/orbWalker/skeleton.atlas",
                        "images/monsters/theForest/orbWalker/skeleton.json",
                        0, "Idle",
                        25, -150, 1.2f));
                break;
            case TRANSIENT:
                creatures.add(new CreatureInfo(
                        "images/monsters/theForest/transient/skeleton.atlas",
                        "images/monsters/theForest/transient/skeleton.json",
                        0, "Idle",
                        0, -150, 1.0f));
                break;
            case SNAKE_LADY:
                creatures.add(new CreatureInfo(
                        "images/monsters/theForest/mage/skeleton.atlas",
                        "images/monsters/theForest/mage/skeleton.json",
                        0, "Idle",
                        -110, -165, 1.0f));
                creatures.add(new CreatureInfo(
                        "images/monsters/theForest/mage_dagger/skeleton.atlas",
                        "images/monsters/theForest/mage_dagger/skeleton.json",
                        0, "Idle",
                        100, -40, 1.0f));
                break;
            case NEMESIS:
                creatures.add(new CreatureInfo(
                        "images/monsters/theForest/nemesis/skeleton.atlas",
                        "images/monsters/theForest/nemesis/skeleton.json",
                        0, "Idle",
                        0, -190, 0.9f));
                break;
            case TIME_EATER:
                creatures.add(new CreatureInfo(
                        "images/monsters/theForest/timeEater/skeleton.atlas",
                        "images/monsters/theForest/timeEater/skeleton.json",
                        0, "Idle",
                        0, -150, 0.8f));
                break;
            default:
                creatures.add(new CreatureInfo(
                        "images/monsters/theBottom/slimeS/skeleton.atlas",
                        "images/monsters/theBottom/slimeS/skeleton.json",
                        0, "idle",
                        0, -120, 1.0f));
                break;
        }
    }

    private enum GameState {
        INITIALIZING,
        PLAYING,
        VICTORY,
        DEFEAT
    }

    //neat little packaged class that contains the animation data of a creature, where to render it, and how big to render it
    private static class CreatureInfo {
        AnimationState state;
        AnimationStateData stateData;
        TextureAtlas atlas;
        Skeleton skeleton;
        float renderX;
        float renderY;

        private CreatureInfo(String atlasUrl, String skeletonUrl, int trackIndex, String animationName, float x, float y, float renderScale) {
            loadAnimation(atlasUrl, skeletonUrl, renderScale);
            state.setAnimation(trackIndex, animationName, true);
            renderX = x;
            renderY = y;
        }

        private void loadAnimation(String atlasUrl, String skeletonUrl, float scale) {
            atlas = new TextureAtlas(Gdx.files.internal(atlasUrl));
            SkeletonJson json = new SkeletonJson(atlas);
            json.setScale(scale);
            SkeletonData skeletonData = json.readSkeletonData(Gdx.files.internal(skeletonUrl));
            skeleton = new Skeleton(skeletonData);
            skeleton.setColor(Color.WHITE);
            stateData = new AnimationStateData(skeletonData);
            state = new AnimationState(stateData);
        }
    }
}