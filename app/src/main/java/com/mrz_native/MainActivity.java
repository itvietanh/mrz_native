package com.mrz_native;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.mrz_native.MrzParser.ParsedMrz;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final int REQ_CODE = 101;
    private PreviewView previewView;
    private TextView statusText;
    private View mrzFrame;
    private ExecutorService cameraExecutor;
    private final TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    private volatile boolean isScanning = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        previewView = findViewById(R.id.previewView);
        statusText = findViewById(R.id.statusText);
        mrzFrame = findViewById(R.id.mrz_guide_frame);
        cameraExecutor = Executors.newSingleThreadExecutor();

        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQ_CODE);
        }
    }

    private boolean allPermissionsGranted() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == REQ_CODE) {
            if (allPermissionsGranted()) startCamera(); else {
                Toast.makeText(this, "Yêu cầu quyền Camera", Toast.LENGTH_SHORT).show();
                finish();
            }
        } else super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, imageProxy -> {
                    if (isScanning) {
                        processImageProxy(imageProxy);
                    } else {
                        imageProxy.close();
                    }
                });

                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);
            } catch (ExecutionException | InterruptedException e) {
                Log.e("MRZ", "Lỗi khởi tạo camera", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    // -------- image processing & mapping từ previewView -> imageProxy ----------
    @OptIn(markerClass = ExperimentalGetImage.class)
    private void processImageProxy(ImageProxy imageProxy) {
        if (imageProxy == null || imageProxy.getImage() == null) {
            if (imageProxy != null) imageProxy.close();
            return;
        }

        // Lấy kích thước preview thực tế và frame
        float previewWidth = previewView.getWidth();
        float previewHeight = previewView.getHeight();

        // Nếu preview chưa layout xong thì skip
        if (previewWidth == 0 || previewHeight == 0) {
            imageProxy.close();
            return;
        }

        View overlay = findViewById(R.id.mrz_guide_frame);

        // Tính tọa độ khung vàng trên preview (theo pixel)
        float frameLeft = overlay.getX();
        float frameTop = overlay.getY();
        float frameWidth = overlay.getWidth();
        float frameHeight = overlay.getHeight();

        // Chuyển đổi sang tọa độ của image gốc
        int imageWidth = imageProxy.getWidth();
        int imageHeight = imageProxy.getHeight();

        // Tỷ lệ giữa image và preview
        float scaleX = (float) imageWidth / previewWidth;
        float scaleY = (float) imageHeight / previewHeight;

        // Nhân tỷ lệ để ra vùng cần crop
        int cropLeft = (int) (frameLeft * scaleX);
        int cropTop = (int) (frameTop * scaleY);
        int cropRight = (int) ((frameLeft + frameWidth) * scaleX);
        int cropBottom = (int) ((frameTop + frameHeight) * scaleY);

        // Giới hạn không vượt biên
        cropLeft = Math.max(0, cropLeft);
        cropTop = Math.max(0, cropTop);
        cropRight = Math.min(imageWidth, cropRight);
        cropBottom = Math.min(imageHeight, cropBottom);

        Rect cropRect = new Rect(cropLeft, cropTop, cropRight, cropBottom);
        imageProxy.setCropRect(cropRect);

        InputImage inputImage = InputImage.fromMediaImage(imageProxy.getImage(), imageProxy.getImageInfo().getRotationDegrees());

        recognizer.process(inputImage)
                .addOnSuccessListener(visionText -> {
                    if (isScanning) handleVisionText(visionText);
                    imageProxy.close();
                })
                .addOnFailureListener(e -> {
                    Log.e("MRZ", "ML Kit failed: " + e.getMessage());
                    imageProxy.close();
                });
    }

    // ---------- main MRZ handling ----------
    private void handleVisionText(Text visionText) {
        // Thu thập các dòng, chuẩn hoá nhẹ
        List<OcrLine> lines = new ArrayList<>();
        for (Text.TextBlock block : visionText.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                String raw = line.getText();
                if (raw == null) continue;
                String norm = normalizeLine(raw);
                if (norm.isEmpty()) continue;
                lines.add(new OcrLine(raw, norm));
            }
        }

        // Nếu không có gì thì thông báo
        if (lines.isEmpty()) {
            showMessageOnUi("Đặt MRZ của hộ chiếu vào khung màu vàng", null);
            return;
        }

        // Thử tìm MRZ theo nhiều cách: TD3 (2x44), TD2 (2x36), TD1 (3x30)
        ParsedMrz parsed = findAndParseMrz(lines);
        if (parsed != null) {
            isScanning = false;
            onMrzSuccess(parsed);
            return;
        }

        // Nếu không parse được trực tiếp, thử các heuristic corrections
        ParsedMrz corrected = tryHeuristicCorrectionsMultiple(lines);
        if (corrected != null) {
            isScanning = false;
            onMrzCorrected(corrected);
            return;
        }

        // Không tìm được
        showMessageOnUi("Đặt MRZ của hộ chiếu vào khung màu vàng", null);
    }

    private String normalizeLine(String raw) {
        // Loại bỏ khoảng trắng, chuyển in hoa, giữ A-Z 0-9 < và một số ký tự thường bị MLkit thêm
        String t = raw.replaceAll("\\s+", "").toUpperCase();
        t = t.replaceAll("[^A-Z0-9<]", "");
        return t;
    }

    private ParsedMrz findAndParseMrz(List<OcrLine> lines) {
        // Tạo danh sách chỉ chứa text chuẩn
        List<String> norms = new ArrayList<>();
        for (OcrLine l : lines) norms.add(l.norm);

        // Thử tìm TD1 (3 dòng x ~30)
        for (int i = 0; i + 2 < norms.size(); i++) {
            String a = norms.get(i);
            String b = norms.get(i + 1);
            String c = norms.get(i + 2);
            if (isLengthApprox(a, 30) && isLengthApprox(b, 30) && isLengthApprox(c, 30)) {
                String l1 = padToLength(a, 30);
                String l2 = padToLength(b, 30);
                String l3 = padToLength(c, 30);
                try {
                    ParsedMrz p = MrzParser.parseTD1(l1, l2, l3);
                    if (p != null) return p;
                } catch (Exception ex) {
                    // ignore
                }
            }
        }

        // Thử tìm TD3 (2 dòng x ~44) và TD2 (2 dòng x ~36)
        for (int i = 0; i + 1 < norms.size(); i++) {
            String a = norms.get(i);
            String b = norms.get(i + 1);

            // TD3 candidate (pad to 44)
            if (isLengthApprox(a, 44) || isLengthApprox(b, 44) || (a.startsWith("P") || a.startsWith("V"))) {
                String l1 = padToLength(a, 44);
                String l2 = padToLength(b, 44);
                try {
                    ParsedMrz p = MrzParser.parseTD3(l1, l2);
                    if (p != null) return p;
                } catch (Exception ex) { }
            }

            // TD2 candidate (pad to 36)
            if (isLengthApprox(a, 36) || isLengthApprox(b, 36)) {
                String l1 = padToLength(a, 36);
                String l2 = padToLength(b, 36);
                try {
                    ParsedMrz p = MrzParser.parseTD2(l1, l2);
                    if (p != null) return p;
                } catch (Exception ex) { }
            }

            // Fallback: try pad 44 even if slightly shorter (some OCR trim)
            if (a.length() >= 20 && b.length() >= 20) {
                String l1 = padToLength(a, 44);
                String l2 = padToLength(b, 44);
                try {
                    ParsedMrz p = MrzParser.parseTD3(l1, l2);
                    if (p != null) return p;
                } catch (Exception ex) { }
            }
        }

        return null;
    }

    private boolean isLengthApprox(String s, int target) {
        if (s == null) return false;
        int len = s.length();
        // Khoảng dung sai: +/- 6 ký tự
        return len >= target - 6 && len <= target + 2;
    }

    private String padToLength(String s, int len) {
        if (s == null) s = "";
        s = s.toUpperCase();
        if (s.length() >= len) return s.substring(0, len);
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < len) sb.append('<');
        return sb.toString();
    }

    // ---------- Heuristic corrections (mạnh hơn) ----------
    // Thử nhiều mapping khác nhau và cả tổ hợp 2 mapping
    private ParsedMrz tryHeuristicCorrectionsMultiple(List<OcrLine> lines) {
        // chuẩn danh sách norms
        List<String> norms = new ArrayList<>();
        for (OcrLine l : lines) norms.add(l.norm);

        // các bản đồ cơ bản
        char[][] maps = {
                {'O','0'}, {'Q','0'}, {'D','0'}, // O/Q/D -> 0
                {'I','1'}, {'L','1'}, {'T','7'}, {'Z','2'}, {'S','5'}, {'B','8'}, {'G','6'}
        };

        // thử 1 map
        for (char[] m : maps) {
            List<String> mapped = applyMapToList(norms, m[0], m[1]);
            ParsedMrz p = findAndParseMrzStrings(mapped);
            if (p != null) {
                Log.d("MRZ_CORRECTION", "Single map corrected: " + m[0] + "->" + m[1]);
                return p;
            }
        }

        // thử 2 maps kết hợp (chỉ một số lượng hạn chế để tránh nổ tổ hợp)
        for (int i = 0; i < maps.length; i++) {
            for (int j = i + 1; j < maps.length && j < i + 6; j++) {
                List<String> mapped = applyMapToList(norms, maps[i][0], maps[i][1]);
                mapped = applyMapToList(mapped, maps[j][0], maps[j][1]);
                ParsedMrz p = findAndParseMrzStrings(mapped);
                if (p != null) {
                    Log.d("MRZ_CORRECTION", "Double map corrected: " + maps[i][0] + "->" + maps[i][1] + "," + maps[j][0] + "->" + maps[j][1]);
                    return p;
                }
            }
        }

        // nếu vẫn không được, thử sửa theo vị trí: cố gắng sửa những ký tự trong vùng số (tài liệu, ngày)
        // (Ở đây chúng ta đơn giản: thử chuyển mọi chữ cái thành số theo map phổ biến rồi parse)
        List<String> aggressive = new ArrayList<>();
        for (String s : norms) {
            aggressive.add(aggressiveMap(s));
        }
        ParsedMrz p = findAndParseMrzStrings(aggressive);
        if (p != null) {
            Log.d("MRZ_CORRECTION", "Aggressive mapping worked");
            return p;
        }

        return null;
    }

    private ParsedMrz findAndParseMrzStrings(List<String> norms) {
        // chuyển về dạng OcrLine tạm để tận dụng findAndParseMrz
        List<OcrLine> tmp = new ArrayList<>();
        for (String s : norms) tmp.add(new OcrLine(s, s));
        return findAndParseMrz(tmp);
    }

    private List<String> applyMapToList(List<String> src, char from, char to) {
        List<String> out = new ArrayList<>();
        for (String s : src) out.add(applyMap(s, from, to));
        return out;
    }

    private String applyMap(String s, char from, char to) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == from) sb.append(to); else sb.append(c);
        }
        return sb.toString();
    }

    private String aggressiveMap(String s) {
        if (s == null) return null;
        // map chữ thường gặp -> số
        s = s.replace('O','0').replace('Q','0').replace('D','0')
                .replace('I','1').replace('L','1').replace('Z','2')
                .replace('S','5').replace('B','8').replace('G','6')
                .replace('T','7');
        return s;
    }

    // ---------- UI helpers ----------
    private void onMrzSuccess(ParsedMrz parsed) {
        runOnUiThread(() -> {
            mrzFrame.setBackgroundResource(R.drawable.mrz_frame); // bạn thêm drawable
            String result = "QUÉT THÀNH CÔNG!\n" +
                    "Họ Tên: " + parsed.name + "\n" +
                    "Số HC: " + parsed.documentNumber + "\n" +
                    "Ngày Sinh: " + parsed.dob + "\n" +
                    "Hết Hạn: " + parsed.expiryDate + "\n\n" +
                    "CHẠM ĐỂ QUÉT LẠI";
            statusText.setText(result);
            statusText.setBackgroundColor(0xAA4CAF50);
            statusText.setOnClickListener(v -> resetScanning());
        });
    }

    private void onMrzCorrected(ParsedMrz parsed) {
        runOnUiThread(() -> {
            mrzFrame.setBackgroundResource(R.drawable.mrz_frame);
            String result = "QUÉT THÀNH CÔNG (SỬA LỖI OCR)!\n" +
                    "Họ Tên: " + parsed.name + "\n" +
                    "Số HC: " + parsed.documentNumber + "\n" +
                    "Ngày Sinh: " + parsed.dob + "\n" +
                    "Hết Hạn: " + parsed.expiryDate + "\n\n" +
                    "CHẠM ĐỂ QUÉT LẠI";
            statusText.setText(result);
            statusText.setBackgroundColor(0xAAFF9800);
            statusText.setOnClickListener(v -> resetScanning());
        });
    }

    private void showMessageOnUi(String text, Integer bgColor) {
        runOnUiThread(() -> {
            statusText.setText(text);
            if (bgColor != null) statusText.setBackgroundColor(bgColor);
            else statusText.setBackgroundColor(0x99000000);
            mrzFrame.setBackgroundResource(R.drawable.mrz_frame); // drawable mặc định
            statusText.setOnClickListener(null);
        });
    }

    private void resetScanning() {
        isScanning = true;
        statusText.setText("Đặt MRZ của hộ chiếu vào khung màu vàng");
        statusText.setBackgroundColor(0x99000000);
        statusText.setOnClickListener(null);
        mrzFrame.setBackgroundResource(R.drawable.mrz_frame);
        Toast.makeText(this, "Bắt đầu quét lại...", Toast.LENGTH_SHORT).show();
    }

    // ---------- helpers & small classes ----------
    private static class OcrLine {
        public final String raw;
        public final String norm;
        public OcrLine(String raw, String norm) { this.raw = raw; this.norm = norm; }
    }

    private static class Pair<F,S> {
        public final F first;
        public final S second;
        public Pair(F f, S s) { first=f; second=s;}
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
        recognizer.close();
    }
}
