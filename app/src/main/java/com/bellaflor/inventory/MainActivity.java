package com.bellaflor.inventory;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final int FILE_CHOOSER = 1001;
    private static final int CREATE_FILE = 1002;
    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private String pendingName;
    private String pendingText;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        web = new WebView(this);
        setContentView(web);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(true);
        web.getSettings().setAllowContentAccess(true);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                startActivityForResult(i, FILE_CHOOSER);
                return true;
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if ("file".equals(u.getScheme())) return false;
                startActivity(new Intent(Intent.ACTION_VIEW, u));
                return true;
            }
        });
        web.loadUrl("file:///android_asset/index.html");
    }

    public class Bridge {
        @JavascriptInterface public void saveFile(String name, String text) {
            runOnUiThread(() -> {
                pendingName = (name == null || name.trim().isEmpty()) ? "export.csv" : name;
                pendingText = text == null ? "" : text;
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("text/csv");
                i.putExtra(Intent.EXTRA_TITLE, pendingName);
                startActivityForResult(i, CREATE_FILE);
            });
        }
        @JavascriptInterface public void resolveFnacBatch(String json) {
            new Thread(() -> {
                JSONObject response = new JSONObject(); JSONArray out = new JSONArray(); int checked = 0;
                try {
                    JSONArray items = new JSONArray(json); int max = Math.min(items.length(), 12); JSONArray attempted = new JSONArray();
                    for (int i=0;i<max;i++) {
                        String title = items.getJSONObject(i).optString("title", "").trim();
                        if (title.isEmpty()) continue; checked++; attempted.put(title);
                        JSONObject r = findOnFnac(title); if (r != null) out.put(r);
                        final int progressChecked = checked; final int progressFound = out.length();
                        runOnUiThread(() -> web.evaluateJavascript("document.getElementById('autofnacstatus').textContent='FNAC: "+progressChecked+"/"+max+" consultados · "+progressFound+" EAN encontrados';", null));
                        try { Thread.sleep(180); } catch (InterruptedException ignored) {}
                    }
                    response.put("results", out); response.put("checked", checked); response.put("attempted", attempted);
                    response.put("message", "FNAC España prioritaria; Portugal usado como respaldo.");
                } catch (Exception e) {
                    try { response.put("results", out); response.put("checked", checked); response.put("attempted", attempted); response.put("message", e.getMessage()); } catch (Exception ignored) {}
                }
                final String payload = JSONObject.quote(response.toString());
                runOnUiThread(() -> web.evaluateJavascript("window.onFnacAutoResult("+payload+")", null));
            }).start();
        }
    }

    private JSONObject findOnFnac(String title) {
        JSONObject r = findOnFnacSite(title, "https://www.fnac.es", "es-ES,es;q=0.9");
        if (r != null) return r;
        return findOnFnacSite(title, "https://www.fnac.pt", "pt-PT,pt;q=0.9,es;q=0.7");
    }

    private JSONObject findOnFnacSite(String title, String base, String lang) {
        try {
            String q = URLEncoder.encode(title, "UTF-8");
            String[] searchUrls = new String[]{
                base+"/SearchResult/ResultList.aspx?Search="+q+"&sft=1&sa=0",
                base+"/SearchResult/ResultList.aspx?Search="+q
            };
            String searchHtml = null;
            for (String su : searchUrls) { searchHtml = httpGet(su, lang); if (searchHtml != null && searchHtml.length() > 500) break; }
            if (searchHtml == null) return null;
            Set<String> links = new LinkedHashSet<>();
            Matcher lm = Pattern.compile("href=[\"']([^\"']+/a\\d+[^\"']*)[\"']", Pattern.CASE_INSENSITIVE).matcher(searchHtml);
            while (lm.find() && links.size() < 4) {
                String u = lm.group(1).replace("&amp;", "&");
                if (u.startsWith("/")) u = base+u;
                if (u.startsWith(base+"/")) links.add(u);
            }
            for (String u : links) {
                String html = httpGet(u, lang); if (html == null) continue;
                String ean = extractEan(html); String pageTitle = extractTitle(html);
                if (ean != null && similarity(normalize(title), normalize(pageTitle)) >= 0.62) {
                    JSONObject r = new JSONObject(); r.put("title", pageTitle.isEmpty()?title:pageTitle); r.put("ean", ean); r.put("url", u); r.put("source", base.contains(".es")?"FNAC ES":"FNAC PT"); return r;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String httpGet(String url, String lang) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection)new URL(url).openConnection(); c.setConnectTimeout(6500); c.setReadTimeout(8000); c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36");
            c.setRequestProperty("Accept-Language", lang);
            int code = c.getResponseCode(); if (code < 200 || code >= 400) return null;
            BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(); String line;
            while ((line=br.readLine())!=null && sb.length()<2500000) sb.append(line).append('\n');
            br.close(); return sb.toString();
        } catch (Exception e) { return null; } finally { if (c!=null) c.disconnect(); }
    }
    private String extractEan(String html) {
        Pattern[] ps = new Pattern[]{Pattern.compile("(?:EAN|gtin13|gtin|isbn13)[^0-9]{0,80}(\\d{13})", Pattern.CASE_INSENSITIVE),Pattern.compile("\\\"gtin13\\\"\\s*:\\s*\\\"(\\d{13})\\\"", Pattern.CASE_INSENSITIVE)};
        for (Pattern p:ps) { Matcher m=p.matcher(html); while(m.find()) if(validEan13(m.group(1))) return m.group(1); } return null;
    }
    private boolean validEan13(String v) { if(v==null || !v.matches("\\d{13}")) return false; int sum=0; for(int i=0;i<12;i++) sum+=(v.charAt(i)-'0')*(i%2==1?3:1); return ((10-(sum%10))%10)==(v.charAt(12)-'0'); }
    private String extractTitle(String html) {
        Matcher m=Pattern.compile("<meta[^>]+property=[\\\"']og:title[\\\"'][^>]+content=[\\\"']([^\\\"']+)",Pattern.CASE_INSENSITIVE).matcher(html);
        if(m.find()) return htmlDecode(m.group(1));
        m=Pattern.compile("<title[^>]*>(.*?)</title>",Pattern.CASE_INSENSITIVE|Pattern.DOTALL).matcher(html);
        return m.find()?htmlDecode(m.group(1).replaceAll("<[^>]+>"," ").trim()):"";
    }
    private String htmlDecode(String s){return s.replace("&amp;","&").replace("&quot;","\"").replace("&#39;", "'").replaceAll("\\s+"," ").trim();}
    private String normalize(String s){return s.toLowerCase().replaceAll("[^a-z0-9áéíóúàâãçñ]+"," ").replaceAll("\\s+"," ").trim();}
    private double similarity(String a,String b){if(a.equals(b))return 1; String[] A=a.split(" "),B=b.split(" "); if(A.length==0||B.length==0)return 0; int inter=0,ac=0; java.util.HashSet<String> bs=new java.util.HashSet<>(); for(String x:B)if(x.length()>1)bs.add(x); for(String x:A)if(x.length()>1){ac++;if(bs.contains(x))inter++;} int bc=bs.size(); return (ac+bc)==0?0:(2.0*inter/(ac+bc));}

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER) {
            if (fileCallback != null) {
                Uri[] out = null;
                if (resultCode == RESULT_OK && data != null) {
                    if (data.getClipData()!=null) { int n=data.getClipData().getItemCount(); out=new Uri[n]; for(int i=0;i<n;i++) out[i]=data.getClipData().getItemAt(i).getUri(); }
                    else if (data.getData()!=null) out = new Uri[]{data.getData()};
                }
                fileCallback.onReceiveValue(out); fileCallback = null;
            }
            return;
        }
        if (requestCode == CREATE_FILE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                try (OutputStream os = getContentResolver().openOutputStream(data.getData())) { os.write(pendingText.getBytes(StandardCharsets.UTF_8)); Toast.makeText(this, "Archivo guardado: " + pendingName, Toast.LENGTH_LONG).show(); }
                catch (Exception e) { Toast.makeText(this, "No se pudo guardar: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
            }
            pendingName = null; pendingText = null;
        }
    }
    @Override public void onBackPressed() { if (web != null && web.canGoBack()) web.goBack(); else super.onBackPressed(); }
}
