package com.stiggpwnz.vibes;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.apache.http.client.methods.HttpPost;

import android.media.MediaPlayer;
import android.media.MediaPlayer.OnBufferingUpdateListener;
import android.media.MediaPlayer.OnCompletionListener;
import android.media.MediaPlayer.OnErrorListener;
import android.media.MediaPlayer.OnInfoListener;
import android.media.MediaPlayer.OnPreparedListener;
import android.media.MediaPlayer.OnSeekCompleteListener;
import android.os.AsyncTask;
import android.os.AsyncTask.Status;
import android.os.Handler;
import android.util.Log;

import com.stiggpwnz.vibes.restapi.Song;
import com.stiggpwnz.vibes.restapi.VkontakteException;

public class Player implements OnCompletionListener, OnPreparedListener, OnSeekCompleteListener, OnBufferingUpdateListener, OnErrorListener, OnInfoListener {

	public static interface OnActionListener {

		public void onBufferingStrated();

		public void onBufferingEnded();

		public void onProgressChanged(int progress);

		public void onBufferingUpdate(int percent);

		public void onAuthProblem();

		public void onInternetProblem();

		public void onNewTrack();

	}

	public enum State {
		NOT_PREPARED, PLAYING, PAUSED, PREPARING_FOR_PLAYBACK, PREPARING_FOR_IDLE, SEEKING_FOR_PLAYBACK, SEEKING_FOR_IDLE, NEXT_FOR_PLAYBACK
	}

	private final Runnable progressUpdater = new Runnable() {

		@Override
		public void run() {
			seekBarUpdater();
		}
	};

	private MediaPlayer player;
	private OnActionListener listener;
	private State state;

	private boolean scrobbled;
	private boolean scrobbling;
	private boolean timeStamped;

	private int shufflePosition;
	private int songDuration;

	private long timeStamp;

	private Random random;
	private List<Integer> shuffleQueue;
	private PreparePlayer preparePlayer;

	public int currentTrack;
	public Song current;

	private VibesApplication app;
	private PlayerService service;
	private Settings settings;

	private Handler handler;

	public Player(PlayerService playerService, Handler handler) {
		this.handler = handler;
		setState(State.NOT_PREPARED);

		player = new MediaPlayer();
		player.setOnCompletionListener(this);
		player.setOnPreparedListener(this);
		player.setOnSeekCompleteListener(this);
		player.setOnBufferingUpdateListener(this);
		player.setOnErrorListener(this);
		player.setOnInfoListener(this);

		this.service = playerService;
		app = (VibesApplication) service.getApplication();
		settings = app.getSettings();

		shuffleQueue = new ArrayList<Integer>();
		random = new Random();

		Log.d(VibesApplication.VIBES, "player ready");
	}

	public void release() {
		player.release();
		handler.removeCallbacks(progressUpdater);
	}

	private class Generator extends Thread {

		int seed;

		public Generator(int seed, String name) {
			super(name);
			this.seed = seed;
		}

		@Override
		public void run() {
			synchronized (Player.this) {
				shuffleQueue.clear();
				shuffleQueue.add(seed);
				shufflePosition = 0;
				int n = app.songs.size();
				for (int i = 1; i < n; i++) {
					boolean flag;
					int m;
					do {
						flag = false;
						m = random.nextInt(n);
						for (int pos : shuffleQueue) {
							if (pos == m) {
								flag = true;
								break;
							}
						}
					} while (flag);
					shuffleQueue.add(m);
				}
			}
		}
	}

	public void setLooping(boolean looping) {
		player.setLooping(looping);
	}

	public boolean isLooping() {
		return player.isLooping();
	}

	private void seekBarUpdater() {
		if (getState() == State.PLAYING) {
			int progress = player.getCurrentPosition();
			if (listener != null)
				listener.onProgressChanged(progress);

			if (settings.getSession() != null && !scrobbled && !scrobbling && songDuration > 30000 && (progress >= (songDuration / 2) || (progress / 60000) >= 4))
				new Thread("Scrobbling") {

					@Override
					public void run() {
						scrobbling = true;
						app.getLastFM().scrobble(getCurrentSong(), timeStamp);
						scrobbling = false;
						scrobbled = true;
						app.getLastFM().updateNowPlaying(getCurrentSong());
					}
				}.start();

			handler.postDelayed(progressUpdater, 500);
		} else
			handler.removeCallbacks(progressUpdater);
	}

	private void generateShuffleQueue(int seed) {
		Log.d(VibesApplication.VIBES, "generating queue");
		new Generator(seed, "Generating Random Queue").start();
	}

	public void generateShuffleQueue() {
		generateShuffleQueue(currentTrack);
	}

	private synchronized void setSongUrl(Song song) {
		try {
			app.getVkontakte().setSongUrl(song);
		} catch (VkontakteException e) {
			switch (e.getCode()) {
			case VkontakteException.UNKNOWN_ERROR_OCCURED:
				errorStopPlayback();
				break;

			case VkontakteException.USER_AUTHORIZATION_FAILED:
				authFail();
				break;

			case VkontakteException.TOO_MANY_REQUESTS_PER_SECOND:
				setSongUrl(song);
				break;

			default:
				errorStopPlayback();
			}
		} catch (Exception e) {
			errorStopPlayback();
		}
	}

	public void play(int position) {
		current = null;
		scrobbled = false;
		timeStamped = false;
		if (currentTrack == position
				&& (getState() == State.PAUSED || getState() == State.PLAYING || getState() == State.SEEKING_FOR_IDLE || getState() == State.SEEKING_FOR_PLAYBACK)) {
			setState(State.SEEKING_FOR_PLAYBACK);
			handler.removeCallbacks(progressUpdater);
			player.seekTo(0);
		} else if (currentTrack == position && (getState() == State.PREPARING_FOR_IDLE || getState() == State.PREPARING_FOR_PLAYBACK)) {
			setState(State.PREPARING_FOR_PLAYBACK);
		} else if (currentTrack != position || getState() == State.NOT_PREPARED) {
			currentTrack = position;
			if (state != State.NOT_PREPARED)
				player.reset();
			state = State.PREPARING_FOR_PLAYBACK;
			synchronized (this) {
				List<HttpPost> requests = app.getVkontakte().getAudioUrlRequests();
				if (preparePlayer != null && preparePlayer.getStatus() == Status.RUNNING) {
					Log.e(VibesApplication.VIBES, "cancelling audio url loader: " + requests.size() + " items in queue");
					for (HttpPost request : requests) {
						request.abort();
					}
					preparePlayer.cancel(true);
				}
				requests.clear();
			}
			preparePlayer = new PreparePlayer();
			preparePlayer.execute();
		}
		generateShuffleQueue(position);
	}

	public void play() {
		scrobbled = false;
		timeStamped = false;
		if (getCurrentSong() != null) {
			if (getState() == State.PAUSED || getState() == State.PLAYING || getState() == State.SEEKING_FOR_IDLE) {
				handler.removeCallbacks(progressUpdater);
				setState(State.SEEKING_FOR_PLAYBACK);
				player.seekTo(0);
			} else if (getState() == State.PREPARING_FOR_IDLE) {
				setState(State.PREPARING_FOR_PLAYBACK);
			} else if (getState() == State.NOT_PREPARED || getState() == State.NEXT_FOR_PLAYBACK) {
				if (listener != null)
					listener.onNewTrack();
				synchronized (this) {
					List<HttpPost> requests = app.getVkontakte().getAudioUrlRequests();
					if (preparePlayer != null && preparePlayer.getStatus() == Status.RUNNING) {
						Log.e(VibesApplication.VIBES, "cancelling audio url loader: " + requests.size() + " items in queue");
						for (HttpPost request : requests) {
							request.abort();
						}
						preparePlayer.cancel(true);
					}
					requests.clear();
				}
				preparePlayer = new PreparePlayer();
				preparePlayer.execute();
			}
		}
	}

	private class PreparePlayer extends AsyncTask<Void, Void, Void> {

		@Override
		protected Void doInBackground(Void... params) {
			Thread.currentThread().setName("Preparing New Track");
			if (isCancelled())
				return null;
			if (getState() != State.PLAYING) {
				// telling everyone that we're started
				if (listener != null)
					listener.onBufferingStrated();
				if (isCancelled())
					return null;
				setState(State.PREPARING_FOR_PLAYBACK);
				if (isCancelled())
					return null;
				// remembering current song and synchronizingly setting an url
				// for it, if needed
				int currentSong = Player.this.currentTrack;
				if (getCurrentSong().url == null) {
					if (isCancelled())
						return null;
					setSongUrl(getCurrentSong());
				}
				// finally starting to prepare song if still on this position
				if (currentSong == Player.this.currentTrack) {
					if (isCancelled())
						return null;
					if (getCurrentSong().url == null) {
						errorStopPlayback();
						return null;
					}
					prepare(currentSong);
				}
			}
			return null;
		}

		private void prepare(int currentSong) {
			if (isCancelled())
				return;
			try {
				player.setDataSource(getCurrentSong().url);
				if (isCancelled())
					return;
				Log.d(VibesApplication.VIBES, "preparing " + Player.this.currentTrack + "...");
				player.prepare();
			} catch (IllegalStateException e) {
				// reseting and recursively preparing if in wrong state
				if (Player.this.currentTrack == currentSong) {
					player.reset();
					prepare(currentSong);
				}
			} catch (Exception e) {

			}
			return;
		}

	}

	public int getCurrentPosition() {
		try {
			return player.getCurrentPosition();
		} catch (Exception e) {
			return 0;
		}
	}

	@Override
	public void onPrepared(MediaPlayer mp) {
		Log.d(VibesApplication.VIBES, "prepared " + currentTrack + " and state = " + getState());
		songDuration = player.getDuration();
		if (listener != null)
			listener.onBufferingEnded();
		if (getState() == State.PREPARING_FOR_PLAYBACK) {
			if (listener == null)
				service.makeNotification();

			startPlaying();
			if (settings.getSession() != null)
				new Thread("Updating Now Playing") {

					@Override
					public void run() {
						app.getLastFM().updateNowPlaying(getCurrentSong());
					}
				}.start();

		} else if (getState() == State.PREPARING_FOR_IDLE) {
			setState(State.PAUSED);
		}

	}

	public Song getCurrentSong() {
		if (current != null)
			return current;
		if (app.songs != null && app.songs.size() > 0)
			return app.songs.get(currentTrack);
		return null;
	}

	private void startPlaying() {
		player.start();
		setState(State.PLAYING);
		if (!timeStamped) {
			timeStamp = System.currentTimeMillis() / 1000;
			timeStamped = true;
		}
		seekBarUpdater();
	}

	private void authFail() {
		if (listener != null)
			listener.onAuthProblem();
	}

	private void errorStopPlayback() {
		stop();
		if (listener != null)
			listener.onInternetProblem();
	}

	public void stop() {
		player.reset();
		setState(State.NOT_PREPARED);
		service.cancelNotification();
		if (listener == null)
			service.startWaiter();
		else
			listener.onNewTrack();
	}

	@Override
	public void onBufferingUpdate(MediaPlayer mp, int percent) {
		if (listener != null)
			listener.onBufferingUpdate(percent);
	}

	@Override
	public void onSeekComplete(MediaPlayer mp) {
		if (listener != null)
			listener.onBufferingEnded();
		Log.d(VibesApplication.VIBES, "seeking complete");
		if (getState() == State.SEEKING_FOR_PLAYBACK) {
			setState(State.PLAYING);
			seekBarUpdater();
		} else if (getState() == State.SEEKING_FOR_IDLE)
			setState(State.PAUSED);
	}

	@Override
	public boolean onError(MediaPlayer mp, int what, int extra) {
		Log.e(VibesApplication.VIBES, String.format("catching error: what = %d, extra = %d", what, extra));
		if (what == 1 && extra == -2147483648) {
			Log.e(VibesApplication.VIBES, "reseting while preparing");
			return true;
		} else if (what == 1 && extra == -1002) {
			errorStopPlayback();
			return true;
		} else if (what == 1 && extra == -1004) {
			resetAndPlay();
			return true;
		} else if (what == -38 && extra == 0) {
			// SEEKING WHILE PREPARING
			return true;
		} else if (what == 1 && extra == -11) {
			errorStopPlayback();
			return true;
		} else if (what == 1 && extra == -1) {
			errorStopPlayback();
			return true;
		}
		return false;
	}

	@Override
	public boolean onInfo(MediaPlayer mp, int what, int extra) {
		Log.i(VibesApplication.VIBES, String.format("catching INFO: what = %d, extra = %d", what, extra));
		if (what == 701 && extra == 0) {
			if (listener != null)
				listener.onBufferingStrated();
			return true;
		} else if (what == 702 && extra == 0) {
			if (listener != null)
				listener.onBufferingEnded();
			return true;
		}
		return false;
	}

	public void setListener(OnActionListener onActionListener) {
		this.listener = onActionListener;
	}

	public Song getCurrent() {
		return current;
	}

	public void setCurrent(Song current) {
		this.current = current;
	}

	public void resume() {
		if (listener == null) {
			service.stopWaiter();
		}
		if (getState() == State.NOT_PREPARED)
			play();
		else if (getState() != State.PLAYING && getState() != State.PREPARING_FOR_IDLE) {
			startPlaying();
			if (listener == null) {
				service.makeNotification();
			}
		} else if (getState() == State.PREPARING_FOR_IDLE) {
			setState(State.PREPARING_FOR_PLAYBACK);
		} else if (getState() == State.SEEKING_FOR_IDLE) {
			setState(State.SEEKING_FOR_PLAYBACK);
		}
	}

	public void pause() {
		if (getState() == State.PLAYING) {
			player.pause();
			setState(State.PAUSED);
			handler.removeCallbacks(progressUpdater);
			if (listener == null) {
				service.cancelNotification();
				service.startWaiter();
			}
		} else if (getState() == State.PREPARING_FOR_PLAYBACK) {
			setState(State.PREPARING_FOR_IDLE);
		} else if (getState() == State.SEEKING_FOR_PLAYBACK) {
			setState(State.SEEKING_FOR_IDLE);
		}
	}

	@Override
	public void onCompletion(MediaPlayer mp) {
		scrobbled = false;
		timeStamped = false;
		timeStamp = System.currentTimeMillis() / 1000;
		if (app.songs != null && app.songs.size() > 0) {
			Log.d(VibesApplication.VIBES, "onCompleted: playing next song and state = " + getState());
			if (!settings.getRepeatPlaylist()) {
				if (settings.getShuffle()) {
					if (shufflePosition == app.songs.size() - 1)
						stop();
					else
						next();
				} else {
					if (currentTrack == app.songs.size() - 1)
						stop();
					else
						next();
				}
			} else {
				next();
			}
		} else
			stop();

		if (listener != null)
			listener.onNewTrack();
	}

	public void next() {
		if (app.songs != null && app.songs.size() > 0) {
			Log.d(VibesApplication.VIBES, "Invoking next() and state = " + getState());
			current = null;
			if (settings.getShuffle()) {
				if (++shufflePosition >= app.songs.size())
					shufflePosition = 0;
				currentTrack = shuffleQueue.get(shufflePosition);
			} else {
				if (++currentTrack >= app.songs.size())
					currentTrack = 0;
			}
			resetAndPlay();
		}
	}

	private void resetAndPlay() {
		if (getState() == State.PREPARING_FOR_PLAYBACK || getState() == State.PLAYING || getState() == State.SEEKING_FOR_PLAYBACK || getState() == State.NEXT_FOR_PLAYBACK) {
			player.reset();
			setState(State.NEXT_FOR_PLAYBACK);
			play();
		} else if (getState() == State.PAUSED || getState() == State.PREPARING_FOR_IDLE || getState() == State.SEEKING_FOR_IDLE) {
			player.reset();
			nextForIdle();
		} else {
			nextForIdle();
		}
	}

	public void nextForIdle() {
		setState(State.NOT_PREPARED);
		if (listener != null) {
			listener.onBufferingEnded();
			listener.onNewTrack();
		}
	}

	public void prev() {
		if (app.songs != null && app.songs.size() > 0) {
			Log.d(VibesApplication.VIBES, "Invoking prev() and state = " + getState());
			current = null;
			if (settings.getShuffle()) {
				if (--shufflePosition < 0)
					shufflePosition = app.songs.size() - 1;
				currentTrack = shuffleQueue.get(shufflePosition);
			} else {
				if (--currentTrack < 0)
					currentTrack = app.songs.size() - 1;
			}
			resetAndPlay();
		}
	}

	public void seekTo(int position) {
		Log.d(VibesApplication.VIBES, "seeking...");
		if (getState() == State.PLAYING) {
			handler.removeCallbacks(progressUpdater);
			setState(State.SEEKING_FOR_PLAYBACK);
			player.seekTo(position);
		} else if (getState() == State.PAUSED) {
			setState(State.SEEKING_FOR_IDLE);
			player.seekTo(position);
		}
	}

	public int getSongDuration() {
		return songDuration;
	}

	public synchronized State getState() {
		return state;
	}

	public synchronized void setState(State state) {
		this.state = state;
	}

}
