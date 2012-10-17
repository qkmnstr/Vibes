package com.stiggpwnz.vibes;

import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.EditText;

import com.actionbarsherlock.app.SherlockActivity;

public class AlternativeLoginActivity extends SherlockActivity {

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.alternative_login);
		VibesApplication app = (VibesApplication) getApplication();

		final EditText username = (EditText) findViewById(R.id.editText1);
		final EditText password = (EditText) findViewById(R.id.editText2);

		Button button = (Button) findViewById(R.id.button1);
		button.setOnClickListener(new OnClickListener() {

			@Override
			public void onClick(View v) {
				String name = username.getText().toString();
				String pass = password.getText().toString();

			}
		});
	}

}
