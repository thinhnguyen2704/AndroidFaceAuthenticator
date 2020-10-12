package com.example.faceAuthenticator.utilities

import android.graphics.Rect

data class Prediction( var bbox : Rect, var label : String )