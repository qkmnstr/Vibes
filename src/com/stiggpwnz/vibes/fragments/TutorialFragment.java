package com.stiggpwnz.vibes.fragments;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.GestureDetector.SimpleOnGestureListener;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnTouchListener;
import android.view.ViewGroup;
import android.view.Window;

import com.actionbarsherlock.app.SherlockDialogFragment;
import com.stiggpwnz.vibes.R;

public class TutorialFragment extends SherlockDialogFragment {

	public static interface Listener {
		public void onTutorialSwipe();
	}

	private GestureDetector gestureDetector;
	private Listener listener;

	public TutorialFragment() {

	}

	@Override
	public void onAttach(Activity activity) {
		super.onAttach(activity);
		listener = (Listener) activity;
	}

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		return inflater.inflate(R.layout.tutorial, container);
	}

	@Override
	public void onViewCreated(View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		getDialog().getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
		getDialog().requestWindowFeature(Window.FEATURE_NO_TITLE);
		setCancelable(false);

		gestureDetector = new GestureDetector(getSherlockActivity(), new MySwipeListener());
		view.setOnTouchListener(new OnTouchListener() {

			@Override
			public boolean onTouch(View v, MotionEvent event) {
				return gestureDetector.onTouchEvent(event);
			}
		});
	}

	@Override
	public void onDetach() {
		super.onDetach();
		listener = null;
	}

	private class MySwipeListener extends SimpleOnGestureListener {

		private static final int SWIPE_MIN_DISTANCE = 120;
		private static final int SWIPE_MAX_OFF_PATH = 250;
		private static final int SWIPE_THRESHOLD_VELOCITY = 200;

		@Override
		public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
			try {
				if (Math.abs(e1.getY() - e2.getY()) > SWIPE_MAX_OFF_PATH)
					return false;

				// left to right swipe
				if (e2.getX() - e1.getX() > SWIPE_MIN_DISTANCE && Math.abs(velocityX) > SWIPE_THRESHOLD_VELOCITY) {
					dismissAllowingStateLoss();
					listener.onTutorialSwipe();
				}
			} catch (Exception e) {

			}
			return false;
		}

		@Override
		public boolean onDown(MotionEvent e) {
			return true;
		}

	}

}