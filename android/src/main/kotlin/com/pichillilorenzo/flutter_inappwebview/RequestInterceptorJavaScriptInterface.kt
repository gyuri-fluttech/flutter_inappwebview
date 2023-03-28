package com.pichillilorenzo.flutter_inappwebview

import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.intellij.lang.annotations.Language
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.*

interface OnFormSubmitted {
    fun onSubmitted(
        url: String,
        method: String,
        body: String,
        headers: Map<String, String>,
        trace: String,
        enctype: String?
    )
}

@Suppress("unused")
internal class RequestInterceptorJavaScriptInterface(
    webView: WebView,
    private val onFormSubmitted: OnFormSubmitted
) {

    init {
        Log.i(LOG_TAG, "Adding JavaScript interface to WebView");
        webView.addJavascriptInterface(this, INTERFACE_NAME)
    }

    @JavascriptInterface
    fun recordFormSubmission(
        url: String,
        method: String,
        formParameterList: String,
        headers: String,
        trace: String,
        enctype: String?
    ) {
        val formParameterJsonArray = JSONArray(formParameterList)
        val headerMap = getHeadersAsMap(headers)

        val body = when (enctype) {
            "application/x-www-form-urlencoded" -> {
                headerMap["content-type"] = enctype
                getUrlEncodedFormBody(formParameterJsonArray)
            }
            "multipart/form-data" -> {
                headerMap["content-type"] = "multipart/form-data; boundary=$MULTIPART_FORM_BOUNDARY"
                getMultiPartFormBody(formParameterJsonArray)
            }
            "text/plain" -> {
                headerMap["content-type"] = enctype
                getPlainTextFormBody(formParameterJsonArray)
            }
            else -> {
                Log.e(LOG_TAG, "Incorrect encoding received from JavaScript: $enctype")
                ""
            }
        }

        Log.i(LOG_TAG, "Recorded form submission from JavaScript - $url --- $body")
        onFormSubmitted.onSubmitted(
            url,
            method,
            body,
            headerMap,
            trace,
            enctype
        )
    }

    private fun getHeadersAsMap(headersString: String): MutableMap<String, String> {
        val headersObject = JSONObject(headersString)
        val map = HashMap<String, String>()
        for (key in headersObject.keys()) {
            val lowercaseHeader = key.lowercase(Locale.getDefault())
            map[lowercaseHeader] = headersObject.getString(key)
        }
        return map
    }

    private fun getUrlEncodedFormBody(formParameterJsonArray: JSONArray): String {
        val resultStringBuilder = StringBuilder()
        repeat(formParameterJsonArray.length()) { i ->
            val formParameter = formParameterJsonArray.get(i) as JSONObject
            val name = formParameter.getString("name")
            val value = formParameter.getString("value")
            val encodedValue = URLEncoder.encode(value, "UTF-8")
            if (i != 0) {
                resultStringBuilder.append("&")
            }
            resultStringBuilder.append(name)
            resultStringBuilder.append("=")
            resultStringBuilder.append(encodedValue)
        }
        return resultStringBuilder.toString()
    }

    private fun getMultiPartFormBody(formParameterJsonArray: JSONArray): String {
        val resultStringBuilder = StringBuilder()
        repeat(formParameterJsonArray.length()) { i ->
            val formParameter = formParameterJsonArray.get(i) as JSONObject
            val name = formParameter.getString("name")
            val value = formParameter.getString("value")
            resultStringBuilder.append("--")
            resultStringBuilder.append(MULTIPART_FORM_BOUNDARY)
            resultStringBuilder.append("\n")
            resultStringBuilder.append("Content-Disposition: form-data; name=\"$name\"")
            resultStringBuilder.append("\n\n")
            resultStringBuilder.append(value)
            resultStringBuilder.append("\n")
        }
        resultStringBuilder.append("--")
        resultStringBuilder.append(MULTIPART_FORM_BOUNDARY)
        resultStringBuilder.append("--")
        return resultStringBuilder.toString()
    }

    private fun getPlainTextFormBody(formParameterJsonArray: JSONArray): String {
        val resultStringBuilder = StringBuilder()
        repeat(formParameterJsonArray.length()) { i ->
            val formParameter = formParameterJsonArray.get(i) as JSONObject
            val name = formParameter.getString("name")
            val value = formParameter.getString("value")
            if (i != 0) {
                resultStringBuilder.append("\n")
            }
            resultStringBuilder.append(name)
            resultStringBuilder.append("=")
            resultStringBuilder.append(value)
        }
        return resultStringBuilder.toString()
    }

    companion object {
        private const val LOG_TAG = "RequestInspectorJs"
        private const val MULTIPART_FORM_BOUNDARY = "----WebKitFormBoundaryU7CgQs9WnqlZYKs6"
        private const val INTERFACE_NAME = "RequestInspection"

        @Language("JS")
        private const val JAVASCRIPT_INTERCEPTION_CODE = """
function getFullUrl(url) {
    if (url.startsWith("/")) {
        return location.protocol + '//' + location.host + url;
    } else {
        return url;
    }
}

function recordFormSubmission(form) {
    var jsonArr = [];
    for (i = 0; i < form.elements.length; i++) {
        var parName = form.elements[i].name;
        var parValue = form.elements[i].value;
        var parType = form.elements[i].type;

        jsonArr.push({
            name: parName,
            value: parValue,
            type: parType
        });
    }

    const path = form.attributes['action'] === undefined ? "/" : form.attributes['action'].nodeValue;
    const method = form.attributes['method'] === undefined ? "GET" : form.attributes['method'].nodeValue;
    const url = getFullUrl(path);
    const encType = form.attributes['enctype'] === undefined ? "application/x-www-form-urlencoded" : form.attributes['enctype'].nodeValue;
    const err = new Error();
    $INTERFACE_NAME.recordFormSubmission(
        url,
        method,
        JSON.stringify(jsonArr),
        "{}",
        err.stack,
        encType
    );
}

function handleFormSubmission(e) {
    const form = e ? e.target : this;
    recordFormSubmission(form);
    form._submit();
}

HTMLFormElement.prototype._submit = HTMLFormElement.prototype.submit;
HTMLFormElement.prototype.submit = handleFormSubmission; 
"""

        fun enabledRequestInspection(webView: WebView, extraJavaScriptToInject: String) {
            Log.i(LOG_TAG, "Enabling request inspection")
            webView.evaluateJavascript(
                "javascript: $JAVASCRIPT_INTERCEPTION_CODE\n$extraJavaScriptToInject",
                null
            )
        }
    }
}