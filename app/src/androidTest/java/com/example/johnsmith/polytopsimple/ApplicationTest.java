package com.example.johnsmith.polytopsimple;

import android.app.Application;
import android.graphics.Color;
import android.test.ApplicationTestCase;
import android.util.Log;

/**
 * <a href="http://d.android.com/tools/testing/testing_android.html">Testing Fundamentals</a>
 */
public class ApplicationTest extends ApplicationTestCase<Application> {
    public ApplicationTest() {
        super(Application.class);
        Log.e("Pretests", "Color: " + Color.parseColor("#424242"));
    }
}