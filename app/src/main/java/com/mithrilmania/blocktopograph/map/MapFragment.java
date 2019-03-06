package com.mithrilmania.blocktopograph.map;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.Editable;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.Spinner;
import android.widget.TextView;

import com.google.android.material.snackbar.Snackbar;
import com.mithrilmania.blocktopograph.Log;
import com.mithrilmania.blocktopograph.R;
import com.mithrilmania.blocktopograph.World;
import com.mithrilmania.blocktopograph.WorldActivityInterface;
import com.mithrilmania.blocktopograph.chunk.Chunk;
import com.mithrilmania.blocktopograph.chunk.ChunkTag;
import com.mithrilmania.blocktopograph.chunk.NBTChunkData;
import com.mithrilmania.blocktopograph.databinding.MapFragmentBinding;
import com.mithrilmania.blocktopograph.map.locator.AdvancedLocatorFragment;
import com.mithrilmania.blocktopograph.map.marker.AbstractMarker;
import com.mithrilmania.blocktopograph.map.marker.CustomNamedBitmapProvider;
import com.mithrilmania.blocktopograph.map.marker.MarkerImageView;
import com.mithrilmania.blocktopograph.map.picer.PicerFragment;
import com.mithrilmania.blocktopograph.map.renderer.MapType;
import com.mithrilmania.blocktopograph.nbt.EditableNBT;
import com.mithrilmania.blocktopograph.nbt.tags.CompoundTag;
import com.mithrilmania.blocktopograph.nbt.tags.FloatTag;
import com.mithrilmania.blocktopograph.nbt.tags.IntTag;
import com.mithrilmania.blocktopograph.nbt.tags.ListTag;
import com.mithrilmania.blocktopograph.nbt.tags.Tag;
import com.mithrilmania.blocktopograph.util.NamedBitmapProvider;
import com.mithrilmania.blocktopograph.util.NamedBitmapProviderHandle;
import com.mithrilmania.blocktopograph.util.math.DimensionVector3;
import com.qozix.tileview.detail.DetailLevelManager;
import com.qozix.tileview.markers.MarkerLayout;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.databinding.DataBindingUtil;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;


public class MapFragment extends Fragment {

    private final static int MARKER_INTERVAL_CHECK = 50;
    public static final String TAG_PICER = "picer";
    //static, remember choice while app is open.
    static Map<NamedBitmapProvider, BitmapChoiceListAdapter.NamedBitmapChoice> markerFilter = new HashMap<>();

    static {
        //entities are enabled by default
        for (Entity v : Entity.values()) {
            //skip things without a bitmap (dropped items etc.)
            //skip entities with placeholder ids (900+)
            if (v.sheetPos < 0 || v.id >= 900) continue;
            markerFilter.put(v.getNamedBitmapProvider(),
                    new BitmapChoiceListAdapter.NamedBitmapChoice(v, true));
        }
        //tile-entities are disabled by default
        for (TileEntity v : TileEntity.values()) {
            markerFilter.put(v.getNamedBitmapProvider(),
                    new BitmapChoiceListAdapter.NamedBitmapChoice(v, false));
        }

    }

    //procedural markers can be iterated while other threads add things to it;
    // iterating performance is not really affected because of the small size;
    public CopyOnWriteArraySet<AbstractMarker> proceduralMarkers = new CopyOnWriteArraySet<>();

    public Set<AbstractMarker> staticMarkers = new HashSet<>();

    public AbstractMarker spawnMarker;
    public AbstractMarker localPlayerMarker;
    private WeakReference<WorldActivityInterface> worldProvider;
    private World world;
    private MCTileProvider minecraftTileProvider;
    private int proceduralMarkersInterval = 0;
    private volatile AsyncTask shrinkProceduralMarkersTask;

    /**
     * Only one floating fragment is allowed at the same time.
     * We use one to display locator.
     */
    private Fragment mFloatingFragment;

    /**
     * Data binding of this fragment view.
     */
    private MapFragmentBinding mBinding;

    @Override
    public void onPause() {
        super.onPause();

        //pause drawing the map
        mBinding.tileView.pause();
    }

    @Override
    public void onStart() {
        super.onStart();
        WorldActivityInterface worldProvider = this.worldProvider.get();
        getActivity().setTitle(world.getWorldDisplayName());
        Log.logFirebaseEvent(getActivity(), Log.CustomFirebaseEvent.MAPFRAGMENT_OPEN);
    }

    @Override
    public void onResume() {
        super.onResume();

        //resume drawing the map
        mBinding.tileView.resume();

        Log.logFirebaseEvent(getActivity(), Log.CustomFirebaseEvent.MAPFRAGMENT_RESUME);
    }

    public void closeChunks() {
        world.getWorldData().resetCache();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
    }

    public String[] getMarkerTapOptions() {
        MarkerTapOption[] values = MarkerTapOption.values();
        int len = values.length;
        String[] options = new String[len];
        for (int i = 0; i < len; i++) {
            options[i] = getString(values[i].stringId);
        }
        return options;
    }

    /**
     * Move map viewer camera to local player's position.
     *
     * <p>
     * Triggered by fab click action.
     * </p>
     *
     * @param view no use. Yes we pass it to snack maker, but we can just use root view instead.
     */
    @UiThread
    private void moveCameraToPlayer(View view) {
        try {

            Activity activity = getActivity();
            if (activity == null) return;

            DimensionVector3<Float> playerPos = world.getPlayerPos();

            Snackbar.make(mBinding.tileView,
                    getString(R.string.something_at_xyz_dim_float, getString(R.string.player),
                            playerPos.x, playerPos.y, playerPos.z, playerPos.dimension.name),
                    Snackbar.LENGTH_SHORT)
                    .setAction("Action", null).show();

            WorldActivityInterface worldProvider = this.worldProvider.get();
            if (playerPos.dimension != worldProvider.getDimension()) {
                worldProvider.changeMapType(playerPos.dimension.defaultMapType, playerPos.dimension);
            }
            Log.logFirebaseEvent(activity, Log.CustomFirebaseEvent.GPS_LOCATE);

            frameTo((double) playerPos.x, (double) playerPos.z);

        } catch (Exception e) {
            Log.d(this, e);
            Snackbar.make(view, R.string.failed_find_player, Snackbar.LENGTH_LONG)
                    .show();
        }
    }

    /**
     * Move map viewer camera to world spawn position.
     *
     * <p>
     * Triggered by fab click action.
     * </p>
     *
     * @param view no use. Yes we pass it to snack maker, but we can just use root view instead.
     */
    @UiThread
    private void moveCameraToSpawn(View view) {
        try {

            Activity activity = getActivity();
            if (activity == null) return;

            DimensionVector3<Integer> spawnPos = world.getSpawnPos();

            Snackbar.make(mBinding.tileView,
                    getString(R.string.something_at_xyz_dim_int, getString(R.string.spawn),
                            spawnPos.x, spawnPos.y, spawnPos.z, spawnPos.dimension.name),
                    Snackbar.LENGTH_SHORT)
                    .setAction("Action", null).show();

            WorldActivityInterface worldProvider = MapFragment.this.worldProvider.get();
            if (spawnPos.dimension != worldProvider.getDimension()) {
                worldProvider.changeMapType(spawnPos.dimension.defaultMapType, spawnPos.dimension);
            }

            Log.logFirebaseEvent(activity, Log.CustomFirebaseEvent.GPS_LOCATE);

            frameTo((double) spawnPos.x, (double) spawnPos.z);

        } catch (Exception e) {
            e.printStackTrace();
            Snackbar.make(view, R.string.failed_find_spawn, Snackbar.LENGTH_LONG)
                    .setAction("Action", null).show();
        }
    }

    /**
     * Callback to close the ongoing floating pane.
     */
    @UiThread
    private void closeFloatPane() {
        if (mFloatingFragment != null) {
            FragmentManager fm = getChildFragmentManager();
            FragmentTransaction trans = fm.beginTransaction();
            trans.remove(mFloatingFragment);
            trans.commit();
            mFloatingFragment = null;
        }
    }

    /**
     * Show a floating pane fragment. Will remove already existing one.
     *
     * @param fragment pane fragment to be attached.
     */
    @UiThread
    private void openFloatPane(@NotNull FloatPaneFragment fragment) {
        FragmentManager fm = getChildFragmentManager();
        fragment.setOnCloseButtonClickListener(this::closeFloatPane);
        FragmentTransaction trans = fm.beginTransaction();
        // Remove existing float pane.
        if (mFloatingFragment != null) trans.remove(mFloatingFragment);
        trans.add(R.id.float_window_container, fragment).commit();
        mFloatingFragment = fragment;
    }

    @Nullable
    @Override
    public View onCreateView(@NotNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {

        //TODO handle savedInstance...

        mBinding = DataBindingUtil.inflate(
                inflater, R.layout.map_fragment, container, false);
        mBinding.tileView.setSelectionView(mBinding.selectionBoard);
        mBinding.selectionBoard.setTileView(mBinding.tileView);

        final Activity activity = getActivity();

        if (activity == null) {
            Log.e(this, new Exception("MapFragment: activity is null, cannot set worldProvider!"));
            return null;
        }

        assert activity instanceof WorldActivityInterface;
        WorldActivityInterface activityInterface = (WorldActivityInterface) activity;
        worldProvider = new WeakReference<>(activityInterface);
        world = activityInterface.getWorld();

        // GPS button: moves camera to player position
        mBinding.fabMenuGpsPlayer.setOnClickListener(this::moveCameraToPlayer);

        // GPS button: moves camera to spawn
        mBinding.fabMenuGpsSpawn.setOnClickListener(this::moveCameraToSpawn);

        // Display a menu allowing user to move camera to many places.
        mBinding.fabMenuGpsOthers.setOnClickListener(unusedView ->
                openFloatPane(AdvancedLocatorFragment.create(world, this::frameTo)));

        mBinding.fabMenuGpsPicer.setOnClickListener(unusedView -> {
            WorldActivityInterface worldProvider = this.worldProvider.get();
            if (worldProvider == null) return;
            DialogFragment fragment = PicerFragment.create(world,
                    new DimensionVector3<>(0, 0, 0, worldProvider.getDimension()));
            fragment.show(getChildFragmentManager(), TAG_PICER);
        });

        // Show the toolbar if the fab menu is opened
        mBinding.fabMenu.setOnMenuToggleListener(opened -> {
            WorldActivityInterface worldProvider = MapFragment.this.worldProvider.get();
            if (opened) worldProvider.showActionBar();
            else worldProvider.hideActionBar();
        });


        try {
            Entity.loadEntityBitmaps(activity.getAssets());
        } catch (IOException e) {
            Log.d(this, e);
        }

        try {
            Block.loadBitmaps(activity.getAssets());
        } catch (IOException e) {
            Log.d(this, e);
        }
        try {
            CustomIcon.loadCustomBitmaps(activity.getAssets());
        } catch (IOException e) {
            Log.d(this, e);
        }

        //set the map-type
        MapTileView tileView = mBinding.tileView;
        tileView.getDetailLevelManager().setLevelType(worldProvider.get().getMapType());

        tileView.setOnLongPressListener(this::onLongPressed);

        /*
        Create tile(=bitmap) provider
         */
        this.minecraftTileProvider = new MCTileProvider(worldProvider.get());



        /*
        Set the bitmap-provider of the tile view
         */
        tileView.setBitmapProvider(this.minecraftTileProvider);


        /*
        Change tile view settings
         */

        tileView.setBackgroundColor(0xFF494E8E);

        // markers should align to the coordinate along the horizontal center and vertical bottom
        tileView.setMarkerAnchorPoints(-0.5f, -1.0f);

        tileView.defineBounds(
                -1.0,
                -1.0,
                1.0,
                1.0
        );

        tileView.setSize(MCTileProvider.viewSizeW, MCTileProvider.viewSizeL);

        for (MapType mapType : MapType.values()) {
            tileView.addDetailLevel(0.0625f, "0.0625", MCTileProvider.TILESIZE, MCTileProvider.TILESIZE, mapType);// 1/(1/16)=16 chunks per tile
            tileView.addDetailLevel(0.125f, "0.125", MCTileProvider.TILESIZE, MCTileProvider.TILESIZE, mapType);
            tileView.addDetailLevel(0.25f, "0.25", MCTileProvider.TILESIZE, MCTileProvider.TILESIZE, mapType);
            tileView.addDetailLevel(0.5f, "0.5", MCTileProvider.TILESIZE, MCTileProvider.TILESIZE, mapType);
            tileView.addDetailLevel(1f, "1", MCTileProvider.TILESIZE, MCTileProvider.TILESIZE, mapType);// 1/1=1 chunk per tile
        }

        tileView.setScale(0.5f);


        boolean framedToPlayer = false;

        //TODO multi-thread this
        try {

            DimensionVector3<Float> playerPos = world.getPlayerPos();
            float x = playerPos.x, y = playerPos.y, z = playerPos.z;
            Log.d(this, "Placed player marker at: " + x + ";" + y + ";" + z + " [" + playerPos.dimension.name + "]");
            localPlayerMarker = new AbstractMarker((int) x, (int) y, (int) z,
                    playerPos.dimension, new CustomNamedBitmapProvider(Entity.PLAYER, "~local_player"), false);
            this.staticMarkers.add(localPlayerMarker);
            addMarker(localPlayerMarker);

            WorldActivityInterface worldProvider = this.worldProvider.get();
            if (localPlayerMarker.dimension != worldProvider.getDimension()) {
                worldProvider.changeMapType(localPlayerMarker.dimension.defaultMapType, localPlayerMarker.dimension);
            }

            frameTo((double) x, (double) z);
            framedToPlayer = true;

        } catch (Exception e) {
            e.printStackTrace();
            Log.d(this, "Failed to place player marker. " + e.toString());
        }


        try {
            DimensionVector3<Integer> spawnPos = world.getSpawnPos();

            spawnMarker = new AbstractMarker(spawnPos.x, spawnPos.y, spawnPos.z, spawnPos.dimension,
                    new CustomNamedBitmapProvider(CustomIcon.SPAWN_MARKER, "Spawn"), false);
            this.staticMarkers.add(spawnMarker);
            addMarker(spawnMarker);

            if (!framedToPlayer) {

                WorldActivityInterface worldProvider = this.worldProvider.get();
                if (spawnMarker.dimension != worldProvider.getDimension()) {
                    worldProvider.changeMapType(spawnMarker.dimension.defaultMapType, spawnMarker.dimension);
                }

                frameTo((double) spawnPos.x, (double) spawnPos.z);
            }

        } catch (Exception e) {
            //no spawn defined...
            if (!framedToPlayer) frameTo(0.0, 0.0);

        }


        tileView.getMarkerLayout().setMarkerTapListener(new MarkerLayout.MarkerTapListener() {
            @Override
            public void onMarkerTap(View view, int tapX, int tapY) {
                if (!(view instanceof MarkerImageView)) {
                    Log.d(this, "Markertaplistener found a marker that is not a MarkerImageView! " + view.toString());
                    return;
                }

                final AbstractMarker marker = ((MarkerImageView) view).getMarkerHook();
                if (marker == null) {
                    Log.d(this, "abstract marker is null! " + view.toString());
                    return;
                }

                AlertDialog.Builder builder = new AlertDialog.Builder(activity);
                builder
                        .setTitle(String.format(getString(R.string.marker_info), marker.getNamedBitmapProvider().getBitmapDisplayName(), marker.getNamedBitmapProvider().getBitmapDataName(), marker.x, marker.y, marker.z, marker.dimension))
                        .setItems(getMarkerTapOptions(), new DialogInterface.OnClickListener() {
                            @SuppressWarnings("RedundantCast")
                            public void onClick(DialogInterface dialog, int which) {

                                final MarkerTapOption chosen = MarkerTapOption.values()[which];

                                switch (chosen) {
                                    case TELEPORT_LOCAL_PLAYER: {
                                        try {
                                            final EditableNBT playerEditable = worldProvider.get().getEditablePlayer();
                                            if (playerEditable == null)
                                                throw new Exception("Player is null");

                                            Iterator playerIter = playerEditable.getTags().iterator();
                                            if (!playerIter.hasNext())
                                                throw new Exception("Player DB entry is empty!");

                                            //db entry consists of one compound tag
                                            final CompoundTag playerTag = (CompoundTag) playerIter.next();

                                            ListTag posVec = (ListTag) playerTag.getChildTagByKey("Pos");

                                            if (posVec == null)
                                                throw new Exception("No \"Pos\" specified");

                                            final List<Tag> playerPos = posVec.getValue();
                                            if (playerPos == null)
                                                throw new Exception("No \"Pos\" specified");
                                            if (playerPos.size() != 3)
                                                throw new Exception("\"Pos\" value is invalid. value: " + posVec.getValue().toString());

                                            IntTag dimensionId = (IntTag) playerTag.getChildTagByKey("DimensionId");
                                            if (dimensionId == null || dimensionId.getValue() == null)
                                                throw new Exception("No \"DimensionId\" specified");


                                            int newX = marker.x;
                                            int newY = marker.y;
                                            int newZ = marker.z;
                                            Dimension newDimension = marker.dimension;

                                            ((FloatTag) playerPos.get(0)).setValue(((float) newX) + 0.5f);
                                            ((FloatTag) playerPos.get(1)).setValue(((float) newY) + 0.5f);
                                            ((FloatTag) playerPos.get(2)).setValue(((float) newZ) + 0.5f);
                                            dimensionId.setValue(newDimension.id);


                                            if (playerEditable.save()) {

                                                localPlayerMarker = moveMarker(localPlayerMarker, newX, newY, newZ, newDimension);

                                                //TODO could be improved for translation friendliness
                                                Snackbar.make(mBinding.tileView,
                                                        activity.getString(R.string.teleported_player_to_xyz_dimension) + newX + ";" + newY + ";" + newZ + " [" + newDimension.name + "] (" + marker.getNamedBitmapProvider().getBitmapDisplayName() + ")",
                                                        Snackbar.LENGTH_LONG)
                                                        .setAction("Action", null).show();

                                            } else throw new Exception("Failed saving player");

                                        } catch (Exception e) {
                                            Log.d(this, e.toString());

                                            Snackbar.make(mBinding.tileView, R.string.failed_teleporting_player,
                                                    Snackbar.LENGTH_LONG)
                                                    .setAction("Action", null).show();
                                        }
                                        return;
                                    }
                                    case REMOVE_MARKER: {
                                        if (marker.isCustom) {
                                            MapFragment.this.removeMarker(marker);
                                            MarkerManager mng = world.getMarkerManager();
                                            mng.removeMarker(marker, true);

                                            mng.save();

                                        } else {
                                            //only custom markers are meant to be removable
                                            Snackbar.make(mBinding.tileView, R.string.marker_is_not_removable,
                                                    Snackbar.LENGTH_LONG)
                                                    .setAction("Action", null).show();
                                        }
                                    }
                                }
                            }
                        })
                        .setCancelable(true)
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();

            }
        });


        //do not loop the scale
        tileView.setShouldLoopScale(false);

        //prevents flickering
        tileView.setTransitionsEnabled(false);

        //more responsive rendering
        tileView.setShouldRenderWhilePanning(false);

        tileView.setSaveEnabled(true);


        return mBinding.getRoot();
    }

    /**
     * Creates a new marker (looking exactly the same as the old one) on the new position,
     * while removing the old marker.
     *
     * @param marker    Marker to be recreated
     * @param x         new pos X
     * @param y         new pos Y
     * @param z         new pos Z
     * @param dimension new pos Dimension
     * @return the newly created marker, which should replace the old one.
     */
    public AbstractMarker moveMarker(AbstractMarker marker, int x, int y, int z, Dimension dimension) {
        AbstractMarker newMarker = marker.copy(x, y, z, dimension);
        if (staticMarkers.remove(marker)) staticMarkers.add(newMarker);
        this.removeMarker(marker);
        this.addMarker(newMarker);

        if (marker.isCustom) {
            MarkerManager mng = world.getMarkerManager();
            mng.removeMarker(marker, true);
            mng.addMarker(newMarker, true);
        }

        return newMarker;
    }

    /**
     * Calculates viewport of tileview, expressed in blocks.
     *
     * @param marginX horizontal viewport-margin, in pixels
     * @param marginZ vertical viewport-margin, in pixels
     * @return minimum_X, maximum_X, minimum_Z, maximum_Z, dimension. (min and max are expressed in blocks!)
     */
    public Object[] calculateViewPort(int marginX, int marginZ) {

        Dimension dimension = this.worldProvider.get().getDimension();

        // 1 chunk per tile on scale 1.0
        int pixelsPerBlockW_unscaled = MCTileProvider.TILESIZE / 16;
        int pixelsPerBlockL_unscaled = MCTileProvider.TILESIZE / 16;

        MapTileView tileView = mBinding.tileView;
        float scale = tileView.getScale();

        //scale the amount of pixels, less pixels per block if zoomed out
        double pixelsPerBlockW = pixelsPerBlockW_unscaled * scale;
        double pixelsPerBlockL = pixelsPerBlockL_unscaled * scale;

        long blockX = Math.round((tileView.getScrollX() - marginX) / pixelsPerBlockW) - MCTileProvider.HALF_WORLDSIZE;
        long blockZ = Math.round((tileView.getScrollY() - marginZ) / pixelsPerBlockL) - MCTileProvider.HALF_WORLDSIZE;
        long blockW = Math.round((tileView.getWidth() + marginX + marginX) / pixelsPerBlockW);
        long blockH = Math.round((tileView.getHeight() + marginZ + marginZ) / pixelsPerBlockL);

        return new Object[]{blockX, blockX + blockW, blockZ, blockH, dimension};
    }

    /**
     * Important: this method should be run from the UI thread.
     *
     * @param marker The marker to remove from the tile view.
     */
    public void removeMarker(AbstractMarker marker) {
        staticMarkers.remove(marker);
        proceduralMarkers.remove(marker);
        if (marker.view != null && mBinding.tileView != null)
            mBinding.tileView.removeMarker(marker.view);
    }

    /**
     * Important: this method should be run from the UI thread.
     *
     * @param marker The marker to add to the tile view.
     */
    public synchronized void addMarker(AbstractMarker marker) {

        Activity act = getActivity();
        if (act == null) return;

        for (AbstractMarker abstractMarker : proceduralMarkers) {
            if (abstractMarker.equals(marker)) {
                removeMarker(abstractMarker);
            }
        }

        if (shrinkProceduralMarkersTask == null
                && ++proceduralMarkersInterval > MARKER_INTERVAL_CHECK) {
            //shrink set of markers to viewport every so often

            DisplayMetrics displayMetrics = act.getResources().getDisplayMetrics();
            //reset this to start accepting viewport update requests again.
            shrinkProceduralMarkersTask = new RetainViewPortMarkersTask(this, () -> {
                //reset this to start accepting viewport update requests again.
                shrinkProceduralMarkersTask = null;
            }).execute(calculateViewPort(
                    displayMetrics.widthPixels / 2,
                    displayMetrics.heightPixels / 2)
            );

            proceduralMarkersInterval = 0;
        }

        proceduralMarkers.add(marker);

        MarkerImageView markerView = marker.getView(act);

        this.filterMarker(marker);

        if (mBinding.tileView.getMarkerLayout().indexOfChild(markerView) >= 0) {
            mBinding.tileView.getMarkerLayout().removeMarker(markerView);
        }


        mBinding.tileView.addMarker(markerView,
                marker.dimension.dimensionScale * (double) marker.x / (double) MCTileProvider.HALF_WORLDSIZE,
                marker.dimension.dimensionScale * (double) marker.z / (double) MCTileProvider.HALF_WORLDSIZE,
                -0.5f, -0.5f);
    }

    public void toggleMarkers() {
        WorldActivityInterface worldProvider = this.worldProvider.get();
        if (worldProvider == null) return;
        if (worldProvider.getShowMarkers()) {
            resetTileView();
        } else {
            for (AbstractMarker marker : proceduralMarkers) {
                if (staticMarkers.contains(marker)) continue;
                removeMarker(marker);
            }
            //resetTileView();
        }
    }

    public String[] getLongClickOptions() {
        LongClickOption[] values = LongClickOption.values();
        int len = values.length;
        String[] options = new String[len];
        for (int i = 0; i < len; i++) {
            options[i] = getString(values[i].stringId);
        }
        return options;
    }

    /**
     * Map long press handler, shows a list of options.
     *
     * @param event long press event.
     */
    public void onLongPressed(MotionEvent event) {

        Dimension dimension = worldProvider.get().getDimension();

        // 1 chunk per tile on scale 1.0
        int pixelsPerBlockW_unscaled = MCTileProvider.TILESIZE / dimension.chunkW;
        int pixelsPerBlockL_unscaled = MCTileProvider.TILESIZE / dimension.chunkL;

        float scale = mBinding.tileView.getScale();
        float pixelsPerBlockScaledW = pixelsPerBlockW_unscaled * scale;
        float pixelsPerBlockScaledL = pixelsPerBlockL_unscaled * scale;


        double worldX = (((mBinding.tileView.getScrollX() + event.getX()) / pixelsPerBlockScaledW) - MCTileProvider.HALF_WORLDSIZE) / dimension.dimensionScale;
        double worldZ = (((mBinding.tileView.getScrollY() + event.getY()) / pixelsPerBlockScaledL) - MCTileProvider.HALF_WORLDSIZE) / dimension.dimensionScale;

        //MapFragment.this.onLongClick(worldX, worldZ);

        final Activity activity = getActivity();


        final Dimension dim = this.worldProvider.get().getDimension();

        double chunkX = worldX / dim.chunkW;
        double chunkZ = worldZ / dim.chunkL;

        //negative doubles are rounded up when casting to int; floor them
        final int chunkXint = chunkX < 0 ? (((int) chunkX) - 1) : ((int) chunkX);
        final int chunkZint = chunkZ < 0 ? (((int) chunkZ) - 1) : ((int) chunkZ);


        final View container = activity.findViewById(R.id.world_content);
        if (container == null) {
            Log.d(this, "CANNOT FIND MAIN CONTAINER, WTF");
            return;
        }

        new AlertDialog.Builder(activity)
                .setTitle(getString(R.string.postion_2D_floats_with_chunkpos, worldX, worldZ, chunkXint, chunkZint, dim.name))
                .setItems(getLongClickOptions(), (dialog, which) -> {

                    final LongClickOption chosen = LongClickOption.values()[which];


                    switch (chosen) {
                        case TELEPORT_LOCAL_PLAYER: {
                            try {

                                final EditableNBT playerEditable = MapFragment.this.worldProvider.get().getEditablePlayer();
                                if (playerEditable == null)
                                    throw new Exception("Player is null");

                                Iterator playerIter = playerEditable.getTags().iterator();
                                if (!playerIter.hasNext())
                                    throw new Exception("Player DB entry is empty!");

                                //db entry consists of one compound tag
                                final CompoundTag playerTag = (CompoundTag) playerIter.next();

                                ListTag posVec = (ListTag) playerTag.getChildTagByKey("Pos");

                                if (posVec == null) throw new Exception("No \"Pos\" specified");

                                final List<Tag> playerPos = posVec.getValue();
                                if (playerPos == null)
                                    throw new Exception("No \"Pos\" specified");
                                if (playerPos.size() != 3)
                                    throw new Exception("\"Pos\" value is invalid. value: " + posVec.getValue().toString());

                                final IntTag dimensionId = (IntTag) playerTag.getChildTagByKey("DimensionId");
                                if (dimensionId == null || dimensionId.getValue() == null)
                                    throw new Exception("No \"DimensionId\" specified");


                                float playerY = (float) playerPos.get(1).getValue();


                                View yForm = LayoutInflater.from(activity).inflate(R.layout.y_coord_form, null);
                                final EditText yInput = yForm.findViewById(R.id.y_input);
                                yInput.setText(String.valueOf(playerY));

                                final float newX = (float) worldX;
                                final float newZ = (float) worldZ;

                                new AlertDialog.Builder(activity)
                                        .setTitle(chosen.stringId)
                                        .setView(yForm)
                                        .setPositiveButton(R.string.action_teleport, (dialog1, which1) -> {

                                            float newY;
                                            Editable value = yInput.getText();
                                            if (value == null) newY = 64f;
                                            else {
                                                try {
                                                    newY = (float) Float.parseFloat(value.toString());//removes excessive precision
                                                } catch (Exception e) {
                                                    newY = 64f;
                                                }
                                            }

                                            ((FloatTag) playerPos.get(0)).setValue(newX);
                                            ((FloatTag) playerPos.get(1)).setValue(newY);
                                            ((FloatTag) playerPos.get(2)).setValue(newZ);
                                            dimensionId.setValue(dim.id);

                                            if (playerEditable.save()) {

                                                MapFragment.this.localPlayerMarker = MapFragment.this.moveMarker(
                                                        MapFragment.this.localPlayerMarker,
                                                        (int) newX, (int) newY, (int) newZ, dim);

                                                Snackbar.make(container,
                                                        String.format(getString(R.string.teleported_player_to_xyz_dim), newX, newY, newZ, dim.name),
                                                        Snackbar.LENGTH_LONG)
                                                        .setAction("Action", null).show();
                                            } else {
                                                Snackbar.make(container,
                                                        R.string.failed_teleporting_player,
                                                        Snackbar.LENGTH_LONG)
                                                        .setAction("Action", null).show();
                                            }
                                        })
                                        .setCancelable(true)
                                        .setNegativeButton(android.R.string.cancel, null)
                                        .show();
                                return;

                            } catch (Exception e) {
                                e.printStackTrace();
                                Snackbar.make(container, R.string.failed_to_find_or_edit_local_player_data,
                                        Snackbar.LENGTH_LONG)
                                        .setAction("Action", null).show();
                                return;
                            }
                        }
                        case CREATE_MARKER: {

                            View createMarkerForm = LayoutInflater.from(activity).inflate(R.layout.create_marker_form, null);

                            final EditText markerNameInput = createMarkerForm.findViewById(R.id.marker_displayname_input);
                            markerNameInput.setText(R.string.default_custom_marker_name);
                            final EditText markerIconNameInput = createMarkerForm.findViewById(R.id.marker_iconname_input);
                            markerIconNameInput.setText("blue_marker");
                            final EditText xInput = createMarkerForm.findViewById(R.id.x_input);
                            xInput.setText(String.valueOf((int) worldX));
                            final EditText yInput = createMarkerForm.findViewById(R.id.y_input);
                            yInput.setText(String.valueOf(64));
                            final EditText zInput = createMarkerForm.findViewById(R.id.z_input);
                            zInput.setText(String.valueOf((int) worldZ));


                            new AlertDialog.Builder(activity)
                                    .setTitle(chosen.stringId)
                                    .setView(createMarkerForm)
                                    .setPositiveButton("Create marker", new DialogInterface.OnClickListener() {

                                        void failParseSnackbarReport(int msg) {
                                            Snackbar.make(container, msg,
                                                    Snackbar.LENGTH_LONG)
                                                    .setAction("Action", null).show();
                                        }

                                        @Override
                                        public void onClick(DialogInterface dialog, int which) {

                                            try {
                                                String displayName = markerNameInput.getText().toString();
                                                if (displayName.equals("") || displayName.contains("\"")) {
                                                    failParseSnackbarReport(R.string.marker_invalid_name);
                                                    return;
                                                }
                                                String iconName = markerIconNameInput.getText().toString();
                                                if (iconName.equals("") || iconName.contains("\"")) {
                                                    failParseSnackbarReport(R.string.invalid_icon_name);
                                                    return;
                                                }

                                                int xM, yM, zM;

                                                String xStr = xInput.getText().toString();
                                                try {
                                                    xM = Integer.parseInt(xStr);
                                                } catch (NumberFormatException e) {
                                                    failParseSnackbarReport(R.string.invalid_x_coordinate);
                                                    return;
                                                }
                                                String yStr = yInput.getText().toString();
                                                try {
                                                    yM = Integer.parseInt(yStr);
                                                } catch (NumberFormatException e) {
                                                    failParseSnackbarReport(R.string.invalid_y_coordinate);
                                                    return;
                                                }

                                                String zStr = zInput.getText().toString();
                                                try {
                                                    zM = Integer.parseInt(zStr);
                                                } catch (NumberFormatException e) {
                                                    failParseSnackbarReport(R.string.invalid_z_coordinate);
                                                    return;
                                                }

                                                AbstractMarker marker = MarkerManager.markerFromData(displayName, iconName, xM, yM, zM, dim);
                                                MarkerManager mng = world.getMarkerManager();
                                                mng.addMarker(marker, true);

                                                MapFragment.this.addMarker(marker);

                                                mng.save();

                                            } catch (Exception e) {
                                                e.printStackTrace();
                                                failParseSnackbarReport(R.string.failed_to_create_marker);
                                            }
                                        }
                                    })
                                    .setCancelable(true)
                                    .setNegativeButton(android.R.string.cancel, null)
                                    .show();


                            return;
                        }
                        /* TODO multi player teleporting
                        case TELEPORT_MULTI_PLAYER:{

                            break;
                        }*/
                        case ENTITY:
                        case TILE_ENTITY: {

                            final Chunk chunk = world.getWorldData()
                                    .getChunk(chunkXint, chunkZint, dim);

                            if (!chunkDataNBT(chunk, chosen == LongClickOption.ENTITY)) {
                                Snackbar.make(container, String.format(getString(R.string.failed_to_load_x), getString(chosen.stringId)),
                                        Snackbar.LENGTH_LONG)
                                        .setAction("Action", null).show();
                            }


                        }
                    }


                })
                .setCancelable(true)
                .setNegativeButton(android.R.string.cancel, null)
                .show();

    }

    /**
     * Opens the chunk data nbt editor.
     *
     * @param chunk  the chunk to edit
     * @param entity if it is entity data (True) or block-entity data (False)
     * @return false when the chunk data could not be loaded.
     */
    private boolean chunkDataNBT(Chunk chunk, boolean entity) {
        NBTChunkData chunkData;
        try {
            chunkData = entity ? chunk.getEntity() : chunk.getBlockEntity();
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }

        //just open the editor if the data is there for us to edit it
        this.worldProvider.get().openChunkNBTEditor(chunk.mChunkX, chunk.mChunkZ, chunkData, mBinding.tileView);
        return true;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mBinding != null) {
            mBinding.tileView.destroy();
        }
        // TODO: should we...👇
        //mBinding.tileView = null;
        minecraftTileProvider = null;
    }

    public void openMarkerFilter() {

        final Activity activity = this.getActivity();


        final List<BitmapChoiceListAdapter.NamedBitmapChoice> choices = new ArrayList<>(markerFilter.values());

        //sort on names, nice for the user.
        Collections.sort(choices, (a, b) -> a.namedBitmap.getNamedBitmapProvider().getBitmapDisplayName().compareTo(b.namedBitmap.getNamedBitmapProvider().getBitmapDisplayName()));


        new AlertDialog.Builder(activity)
                .setTitle(R.string.filter_markers)
                .setAdapter(new BitmapChoiceListAdapter(activity, choices), null)
                .setCancelable(true)
                .setPositiveButton(android.R.string.ok, (dialogInterface, i) -> {
                    //save all the temporary states.
                    for (BitmapChoiceListAdapter.NamedBitmapChoice choice : choices) {
                        choice.enabled = choice.enabledTemp;
                    }
                    MapFragment.this.updateMarkerFilter();
                })
                .setNegativeButton(android.R.string.cancel, (dialogInterface, i) -> {
                    //reset all the temporary states.
                    for (BitmapChoiceListAdapter.NamedBitmapChoice choice : choices) {
                        choice.enabledTemp = choice.enabled;
                    }
                })
                .setOnCancelListener(dialogInterface -> {
                    //reset all the temporary states.
                    for (BitmapChoiceListAdapter.NamedBitmapChoice choice : choices) {
                        choice.enabledTemp = choice.enabled;
                    }
                })
                .show();
    }

    public void updateMarkerFilter() {
        for (AbstractMarker marker : this.proceduralMarkers) {
            filterMarker(marker);
        }
    }

    public void filterMarker(AbstractMarker marker) {
        WorldActivityInterface worldProvider = this.worldProvider.get();
        BitmapChoiceListAdapter.NamedBitmapChoice choice = markerFilter.get(marker.getNamedBitmapProvider());
        if (choice != null) {
            marker.getView(this.getActivity()).setVisibility(
                    (choice.enabled && marker.dimension == worldProvider.getDimension())
                            ? View.VISIBLE : View.INVISIBLE);
        } else {
            marker.getView(this.getActivity())
                    .setVisibility(marker.dimension == worldProvider.getDimension()
                            ? View.VISIBLE : View.INVISIBLE);
        }
    }

    public void resetTileView() {
        if (mBinding.tileView != null) {
            WorldActivityInterface worldProvider = this.worldProvider.get();
            Log.logFirebaseEvent(getActivity(), Log.CustomFirebaseEvent.MAPFRAGMENT_RESET);

            updateMarkerFilter();

            mBinding.tileView.getDetailLevelManager().setLevelType(worldProvider.getMapType());

            invalidateTileView();
        }
    }

    public void invalidateTileView() {
        WorldActivityInterface worldProvider = this.worldProvider.get();
        MapType redo = worldProvider.getMapType();
        DetailLevelManager manager = mBinding.tileView.getDetailLevelManager();
        //just swap mapType twice; it is not rendered, but it invalidates all tiles.
        manager.setLevelType(MapType.CHESS);
        manager.setLevelType(redo);
        //all tiles will now reload as soon as the tileView is drawn (user scrolls -> redraw)
    }

    /**
     * This is a convenience method to scrollToAndCenter after layout (which won't happen if called directly in onCreate
     * see https://github.com/moagrius/TileView/wiki/FAQ
     */
    public void frameTo(final double worldX, final double worldZ) {
        mBinding.tileView.post(() -> {
            Dimension dimension = worldProvider.get().getDimension();
            if (mBinding.tileView != null) mBinding.tileView.scrollToAndCenter(
                    dimension.dimensionScale * worldX / (double) MCTileProvider.HALF_WORLDSIZE,
                    dimension.dimensionScale * worldZ / (double) MCTileProvider.HALF_WORLDSIZE);
        });
    }

    public enum MarkerTapOption {

        TELEPORT_LOCAL_PLAYER(R.string.teleport_local_player),
        REMOVE_MARKER(R.string.remove_custom_marker);

        public final int stringId;

        MarkerTapOption(int id) {
            this.stringId = id;
        }

    }

    public enum LongClickOption {

        TELEPORT_LOCAL_PLAYER(R.string.teleport_local_player, null),
        CREATE_MARKER(R.string.create_custom_marker, null),
        //TODO TELEPORT_MULTI_PLAYER("Teleport other player", null),
        ENTITY(R.string.open_chunk_entity_nbt, ChunkTag.ENTITY),
        TILE_ENTITY(R.string.open_chunk_tile_entity_nbt, ChunkTag.BLOCK_ENTITY);

        public final int stringId;
        public final ChunkTag dataType;

        LongClickOption(int id, ChunkTag dataType) {
            this.stringId = id;
            this.dataType = dataType;
        }

    }

//    private class MapTileView extends TileView{@Override
//    public void onLongPress(MotionEvent event) {
//
//        Dimension dimension = worldProvider.get().getDimension();
//
//        // 1 chunk per tile on scale 1.0
//        int pixelsPerBlockW_unscaled = MCTileProvider.TILESIZE / dimension.chunkW;
//        int pixelsPerBlockL_unscaled = MCTileProvider.TILESIZE / dimension.chunkL;
//
//        float pixelsPerBlockScaledW = pixelsPerBlockW_unscaled * this.getScale();
//        float pixelsPerBlockScaledL = pixelsPerBlockL_unscaled * this.getScale();
//
//
//        double worldX = (((this.getScrollX() + event.getX()) / pixelsPerBlockScaledW) - MCTileProvider.HALF_WORLDSIZE) / dimension.dimensionScale;
//        double worldZ = (((this.getScrollY() + event.getY()) / pixelsPerBlockScaledL) - MCTileProvider.HALF_WORLDSIZE) / dimension.dimensionScale;
//
//        MapFragment.this.onLongClick(worldX, worldZ);
//    }}

    public static class MarkerListAdapter extends ArrayAdapter<AbstractMarker> {


        MarkerListAdapter(Context context, List<AbstractMarker> objects) {
            super(context, 0, objects);
        }

        @NonNull
        @Override
        public View getView(int position, View convertView, @NonNull ViewGroup parent) {

            RelativeLayout v = (RelativeLayout) convertView;

            if (v == null) {
                LayoutInflater vi;
                vi = LayoutInflater.from(getContext());
                v = (RelativeLayout) vi.inflate(R.layout.marker_list_entry, parent, false);
            }

            AbstractMarker m = getItem(position);

            if (m != null) {
                TextView name = v.findViewById(R.id.marker_name);
                TextView xz = v.findViewById(R.id.marker_xz);
                ImageView icon = v.findViewById(R.id.marker_icon);

                name.setText(m.getNamedBitmapProvider().getBitmapDisplayName());
                String xzStr = String.format(Locale.ENGLISH, "x: %d, y: %d, z: %d", m.x, m.y, m.z);
                xz.setText(xzStr);

                m.loadIcon(icon);

            }

            return v;
        }

    }

    public static class BitmapChoiceListAdapter extends ArrayAdapter<BitmapChoiceListAdapter.NamedBitmapChoice> {

        private CompoundButton.OnCheckedChangeListener changeListener = new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
                Object tag = compoundButton.getTag();
                if (tag == null) return;
                int position = (int) tag;
                final NamedBitmapChoice m = getItem(position);
                if (m == null) return;
                m.enabledTemp = b;
            }
        };

        BitmapChoiceListAdapter(Context context, List<NamedBitmapChoice> objects) {
            super(context, 0, objects);
        }

        @NonNull
        @Override
        public View getView(final int position, View v, @NonNull ViewGroup parent) {

            final NamedBitmapChoice m = getItem(position);
            if (m == null) return new RelativeLayout(getContext());

            if (v == null) v = LayoutInflater
                    .from(getContext())
                    .inflate(R.layout.img_name_check_list_entry, parent, false);


            ImageView img = v.findViewById(R.id.entry_img);
            TextView text = v.findViewById(R.id.entry_text);
            final CheckBox check = v.findViewById(R.id.entry_check);

            img.setImageBitmap(m.namedBitmap.getNamedBitmapProvider().getBitmap());
            text.setText(m.namedBitmap.getNamedBitmapProvider().getBitmapDisplayName());
            check.setTag(position);
            check.setChecked(m.enabledTemp);
            check.setOnCheckedChangeListener(changeListener);

            return v;
        }

        static class NamedBitmapChoice {

            //Using a handle to facilitate bitmap swapping without breaking the filter.
            final NamedBitmapProviderHandle namedBitmap;
            boolean enabledTemp;
            boolean enabled;

            NamedBitmapChoice(NamedBitmapProviderHandle namedBitmap, boolean enabled) {
                this.namedBitmap = namedBitmap;
                this.enabled = this.enabledTemp = enabled;
            }
        }

    }

    private static class GetPlayerTask extends AsyncTask<Void, Void, String[]> {

        private final WeakReference<MapFragment> owner;
        private final WeakReference<View> view;
        private final WeakReference<Activity> activity;

        private GetPlayerTask(MapFragment owner, View view, Activity activity) {
            this.owner = new WeakReference<>(owner);
            this.view = new WeakReference<>(view);
            this.activity = new WeakReference<>(activity);
        }

        @Override
        protected String[] doInBackground(Void... arg0) {
            try {
                return owner.get().world.getWorldData().getNetworkPlayerNames();
            } catch (Exception e) {
                return null;
            }
        }

        protected void onPostExecute(final String[] players) {
            owner.get().getActivity().runOnUiThread(() -> {

                if (players == null) {
                    Snackbar.make(view.get(), R.string.failed_to_retrieve_player_data, Snackbar.LENGTH_LONG)
                            .setAction("Action", null).show();
                    return;
                }

                if (players.length == 0) {
                    Snackbar.make(view.get(), R.string.no_multiplayer_data_found, Snackbar.LENGTH_LONG)
                            .setAction("Action", null).show();
                    return;
                }


                //NBT tag type spinner
                final Spinner spinner = new Spinner(activity.get());
                ArrayAdapter<String> spinnerArrayAdapter = new ArrayAdapter<>(activity.get(),
                        android.R.layout.simple_spinner_item, players);

                spinnerArrayAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                spinner.setAdapter(spinnerArrayAdapter);


                //wrap layout in alert
                new AlertDialog.Builder(activity.get())
                        .setTitle(R.string.go_to_player)
                        .setView(spinner)
                        .setPositiveButton(R.string.go_loud, new DialogInterface.OnClickListener() {

                            public void onClick(DialogInterface dialog, int whichButton) {

                                //new tag type
                                int spinnerIndex = spinner.getSelectedItemPosition();
                                String playerKey = players[spinnerIndex];

                                try {
                                    DimensionVector3<Float> playerPos = owner.get().world.getMultiPlayerPos(playerKey);

                                    Snackbar.make(owner.get().mBinding.tileView,
                                            owner.get().getString(R.string.something_at_xyz_dim_float,
                                                    playerKey,
                                                    playerPos.x,
                                                    playerPos.y,
                                                    playerPos.z,
                                                    playerPos.dimension.name),
                                            Snackbar.LENGTH_LONG)
                                            .setAction("Action", null).show();

                                    WorldActivityInterface worldProvider = owner.get().worldProvider.get();
                                    Log.logFirebaseEvent(activity.get(), Log.CustomFirebaseEvent.GPS_LOCATE);

                                    if (playerPos.dimension != worldProvider.getDimension()) {
                                        worldProvider.changeMapType(playerPos.dimension.defaultMapType, playerPos.dimension);
                                    }

                                    owner.get().frameTo((double) playerPos.x, (double) playerPos.z);

                                } catch (Exception e) {
                                    Snackbar.make(view.get(), e.getMessage(), Snackbar.LENGTH_LONG)
                                            .setAction("Action", null).show();
                                }

                            }
                        })
                        //or alert is cancelled
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            });
        }

    }

    private static class RetainViewPortMarkersTask extends AsyncTask<Object, AbstractMarker, Void> {

        private final WeakReference<MapFragment> owner;
        private final Runnable callback;

        private RetainViewPortMarkersTask(MapFragment owner, Runnable callback) {
            this.owner = new WeakReference<>(owner);
            this.callback = callback;
        }

        @Override
        protected Void doInBackground(Object... params) {
            long minX = (long) params[0],
                    maxX = (long) params[1],
                    minY = (long) params[2],
                    maxY = (long) params[3];
            Dimension reqDim = (Dimension) params[4];

            CopyOnWriteArraySet<AbstractMarker> proceduralMarkers = owner.get().proceduralMarkers;

            for (AbstractMarker p : proceduralMarkers) {

                // do not remove static markers
                if (owner.get().staticMarkers.contains(p)) continue;

                if (p.x < minX || p.x > maxX || p.y < minY || p.y > maxY || p.dimension != reqDim) {
                    this.publishProgress(p);
                }
            }
            return null;
        }

        @Override
        protected void onProgressUpdate(final AbstractMarker... values) {
            for (AbstractMarker v : values) {
                owner.get().removeMarker(v);
            }
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            callback.run();
        }

    }


}
