# Keep the JavaScript bridge exposed to the viewer WebView.
-keepclassmembers class io.github.monkut.wallscanner.viewer.ViewerActivity$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}
