# WebView 通过 addJavascriptInterface 暴露给网页的方法不能被混淆。
# 当前未使用该能力，保留规则以防后续添加。
-keepattributes JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
