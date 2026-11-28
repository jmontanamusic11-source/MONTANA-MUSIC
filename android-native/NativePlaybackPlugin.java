package com.montanamusic.app;

import android.content.ComponentName;
import android.net.Uri;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

@CapacitorPlugin(name = "NativePlayback")
public final class NativePlaybackPlugin extends Plugin {
    private MediaController controller;
    private ListenableFuture<MediaController> controllerFuture;

    private void withController(PluginCall call, ControllerAction action) {
        if (controller != null) {
            runAction(call, controller, action);
            return;
        }
        if (controllerFuture == null) {
            SessionToken token = new SessionToken(
                getContext(),
                new ComponentName(getContext(), PlaybackService.class)
            );
            controllerFuture = new MediaController.Builder(getContext(), token).buildAsync();
        }
        controllerFuture.addListener(() -> {
            try {
                controller = controllerFuture.get();
                controller.addListener(new Player.Listener() {
                    @Override
                    public void onIsPlayingChanged(boolean isPlaying) {
                        emitState();
                    }

                    @Override
                    public void onMediaItemTransition(MediaItem item, int reason) {
                        emitState();
                    }

                    @Override
                    public void onPlaybackStateChanged(int state) {
                        emitState();
                    }
                });
                runAction(call, controller, action);
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                call.reject("No se pudo conectar con el servicio de reproducción.", e);
            }
        }, getContext().getMainExecutor());
    }

    private void runAction(PluginCall call, MediaController player, ControllerAction action) {
        try {
            action.run(player);
        } catch (Exception e) {
            call.reject("Ocurrió un error al controlar el reproductor nativo.", e);
        }
    }

    private JSObject stateObject() {
        JSObject state = new JSObject();
        if (controller == null) return state;
        MediaItem item = controller.getCurrentMediaItem();
        state.put("mediaId", item == null ? "" : item.mediaId);
        state.put("isPlaying", controller.isPlaying());
        state.put("position", controller.getCurrentPosition());
        state.put("duration", controller.getDuration());
        state.put("playbackState", controller.getPlaybackState());
        return state;
    }

    private void emitState() {
        if (controller != null) notifyListeners("playbackState", stateObject());
    }

    @PluginMethod
    public void playQueue(PluginCall call) {
        JSArray tracks = call.getArray("tracks");
        int startIndex = call.getInt("startIndex", 0);
        if (tracks == null || tracks.length() == 0) {
            call.reject("La lista de reproducción está vacía.");
            return;
        }
        List<MediaItem> items = new ArrayList<>();
        try {
            for (int i = 0; i < tracks.length(); i++) {
                JSONObject track = tracks.getJSONObject(i);
                String url = track.optString("url", "");
                if (url.isEmpty()) continue;
                String title = track.optString("title", "MONTANA MUSIC");
                String artist = track.optString("artist", "Artista independiente");
                String mediaId = track.optString("mediaId", String.valueOf(i));
                String artwork = track.optString("artwork", "");
                MediaMetadata.Builder metadata = new MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist);
                if (!artwork.isEmpty()) metadata.setArtworkUri(Uri.parse(artwork));
                items.add(new MediaItem.Builder()
                    .setMediaId(mediaId)
                    .setUri(Uri.parse(url))
                    .setMediaMetadata(metadata.build())
                    .build());
            }
        } catch (Exception e) {
            call.reject("No se pudo preparar la lista de canciones.", e);
            return;
        }
        if (items.isEmpty()) {
            call.reject("No hay canciones con una dirección de audio válida.");
            return;
        }
        final int safeIndex = Math.max(0, Math.min(startIndex, items.size() - 1));
        withController(call, player -> {
            player.setMediaItems(items, safeIndex, 0L);
            player.prepare();
            player.play();
            call.resolve();
            emitState();
        });
    }

    @PluginMethod
    public void pause(PluginCall call) {
        withController(call, player -> { player.pause(); call.resolve(); emitState(); });
    }

    @PluginMethod
    public void resume(PluginCall call) {
        withController(call, player -> { player.play(); call.resolve(); emitState(); });
    }

    @PluginMethod
    public void next(PluginCall call) {
        withController(call, player -> {
            if (player.hasNextMediaItem()) player.seekToNextMediaItem();
            call.resolve();
            emitState();
        });
    }

    @PluginMethod
    public void previous(PluginCall call) {
        withController(call, player -> {
            if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem();
            else player.seekTo(0L);
            call.resolve();
            emitState();
        });
    }

    @PluginMethod
    public void seekTo(PluginCall call) {
        long position = call.getLong("position", 0L);
        withController(call, player -> { player.seekTo(Math.max(0L, position)); call.resolve(); });
    }

    @PluginMethod
    public void getState(PluginCall call) {
        withController(call, player -> call.resolve(stateObject()));
    }

    @Override
    protected void handleOnDestroy() {
        if (controllerFuture != null) {
            MediaController.releaseFuture(controllerFuture);
            controllerFuture = null;
        }
        controller = null;
    }

    private interface ControllerAction {
        void run(MediaController controller);
    }
}
