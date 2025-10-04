package com.mrz_native;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.AspectRatio;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraControl;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.MeteringPointFactory;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.mrz_native.MrzParser.ParsedMrz;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import android.util.Size;

public class MainActivity extends AppCompatActivity {
    private static final int REQ_CODE = 101;
    private static final long OCR_FRAME_INTERVAL_MS = 120; // throttle OCR
    private static final int REQUIRED_STABLE_HITS = 2; // frames to confirm
    private PreviewView previewView;
    private TextView statusText;
    private View mrzFrame;
    private View torchToggle; // will be ToggleButton
    private ExecutorService cameraExecutor;
    private final TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    private volatile boolean isScanning = true;
    private volatile boolean ocrInFlight = false;
    private volatile long lastOcrTs = 0L;
    private volatile ParsedMrz lastCandidate = null;
    private volatile int stableHits = 0;
    private Camera camera;
    private CameraControl cameraControl;
    private CameraInfo cameraInfo;
    private boolean enableRoiCrop = false; // keep off unless mapping is fully verified

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        previewView = findViewById(R.id.previewView);
        statusText = findViewById(R.id.statusText);
        mrzFrame = findViewById(R.id.mrz_guide_frame);
        torchToggle = findViewById(R.id.torchToggle);
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
                Preview preview = new Preview.Builder()
                        .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                        .build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setTargetResolution(new Size(1920, 1080))
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, imageProxy -> {
                    if (!isScanning) { imageProxy.close(); return; }
                    long now = SystemClock.uptimeMillis();
                    if (ocrInFlight || (now - lastOcrTs) < OCR_FRAME_INTERVAL_MS) {
                        imageProxy.close();
                        return;
                    }
                    lastOcrTs = now;
                    processImageProxy(imageProxy);
                });

                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
                cameraProvider.unbindAll();
                camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);
                cameraControl = camera.getCameraControl();
                cameraInfo = camera.getCameraInfo();

                setupTorchUi();
                // Kick an initial focus/metering on MRZ guide
                previewView.post(this::updateFocusMetering);
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

        // Optional: crop to MRZ guide region when mapping is verified
        if (enableRoiCrop) {
            float previewWidth = previewView.getWidth();
            float previewHeight = previewView.getHeight();
            if (previewWidth > 0 && previewHeight > 0) {
                View overlay = mrzFrame;
                float frameLeft = overlay.getX();
                float frameTop = overlay.getY();
                float frameWidth = overlay.getWidth();
                float frameHeight = overlay.getHeight();

                int imageWidth = imageProxy.getWidth();
                int imageHeight = imageProxy.getHeight();
                float scaleX = (float) imageWidth / previewWidth;
                float scaleY = (float) imageHeight / previewHeight;

                int cropLeft = (int) (frameLeft * scaleX);
                int cropTop = (int) (frameTop * scaleY);
                int cropRight = (int) ((frameLeft + frameWidth) * scaleX);
                int cropBottom = (int) ((frameTop + frameHeight) * scaleY);

                cropLeft = Math.max(0, cropLeft);
                cropTop = Math.max(0, cropTop);
                cropRight = Math.min(imageWidth, cropRight);
                cropBottom = Math.min(imageHeight, cropBottom);

                if (cropRight > cropLeft && cropBottom > cropTop) {
                    Rect cropRect = new Rect(cropLeft, cropTop, cropRight, cropBottom);
                    imageProxy.setCropRect(cropRect);
                }
            }
        }

        InputImage inputImage = InputImage.fromMediaImage(imageProxy.getImage(), imageProxy.getImageInfo().getRotationDegrees());

        ocrInFlight = true;
        recognizer.process(inputImage)
                .addOnSuccessListener(visionText -> {
                    if (isScanning) handleVisionText(visionText);
                    ocrInFlight = false;
                    imageProxy.close();
                })
                .addOnFailureListener(e -> {
                    Log.e("MRZ", "ML Kit failed: " + e.getMessage());
                    ocrInFlight = false;
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
                Rect bb = line.getBoundingBox();
                float cy = bb != null ? (bb.centerY()) : 0f;
                float left = bb != null ? bb.left : 0f;
                float h = bb != null ? (bb.height()) : 0f;
                lines.add(new OcrLine(raw, norm, cy, left, h));
            }
        }

        // Nếu không có gì thì thông báo
        if (lines.isEmpty()) {
            showMessageOnUi("Đặt MRZ của hộ chiếu vào khung màu vàng", null);
            return;
        }

        // Sắp xếp theo vị trí dọc để tăng khả năng gom đúng dòng MRZ
        Collections.sort(lines, Comparator.comparingDouble(l -> l.centerY));

        // Chỉ tập trung hộ chiếu TD3 trước (2x44)
        ParsedMrz parsed = findAndParseMrzTD3Only(lines);
        if (parsed != null) {
            onCandidateDetected(parsed, false);
            return;
        }

        // Nếu không parse được trực tiếp, thử các heuristic corrections
        ParsedMrz corrected = tryHeuristicCorrectionsTD3(lines);
        if (corrected != null) {
            onCandidateDetected(corrected, true);
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

    private void onCandidateDetected(ParsedMrz candidate, boolean corrected) {
        if (candidate == null) return;
        if (lastCandidate != null &&
                safeEquals(lastCandidate.documentNumber, candidate.documentNumber) &&
                safeEquals(lastCandidate.name, candidate.name) &&
                safeEquals(lastCandidate.expiryDate, candidate.expiryDate)) {
            stableHits++;
        } else {
            lastCandidate = candidate;
            stableHits = 1;
        }

        if (stableHits >= REQUIRED_STABLE_HITS) {
            isScanning = false;
            if (corrected) onMrzCorrected(candidate); else onMrzSuccess(candidate);
        } else {
            showMessageOnUi("Đang ổn định MRZ... (" + stableHits + "/" + REQUIRED_STABLE_HITS + ")", null);
        }
    }

    private boolean safeEquals(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }

    private ParsedMrz findAndParseMrzTD3Only(List<OcrLine> lines) {
        // Gom dòng theo hàng (cùng baseline), ghép từ trái sang phải
        List<String> rowCandidates = buildRowCandidates(lines);

        // Thử tìm TD3 (2 dòng x ~44) và TD2 (2 dòng x ~36)
        for (int i = 0; i + 1 < rowCandidates.size(); i++) {
            String a = rowCandidates.get(i);
            String b = rowCandidates.get(i + 1);

            // TD3 candidate (pad to 44)
            if ((isLengthApprox(a, 44) || isLengthApprox(b, 44) || (a.startsWith("P") || a.startsWith("V")))
                    && looksLikeMrzLine(a) && looksLikeMrzLine(b)) {
                String l1 = padToLength(a, 44);
                String l2 = padToLength(b, 44);
                try {
                    ParsedMrz p = MrzParser.parseTD3(l1, l2);
                    if (p != null) return p;
                } catch (Exception ex) { }
                try {
                    ParsedMrz p2 = MrzParser.parseTD3Relaxed(l1, l2);
                    if (p2 != null) return p2;
                } catch (Exception ex) { }
                // thử đảo dòng phòng OCR đảo thứ tự
                try {
                    ParsedMrz p3 = MrzParser.parseTD3(padToLength(b, 44), padToLength(a, 44));
                    if (p3 != null) return p3;
                } catch (Exception ex) { }
                try {
                    ParsedMrz p4 = MrzParser.parseTD3Relaxed(padToLength(b, 44), padToLength(a, 44));
                    if (p4 != null) return p4;
                } catch (Exception ex) { }
            }

            // Fallback: try pad 44 even if slightly shorter (some OCR trim)
            if (a.length() >= 18 && b.length() >= 18 && looksLikeMrzLine(a) && looksLikeMrzLine(b)) {
                String l1 = padToLength(a, 44);
                String l2 = padToLength(b, 44);
                try {
                    ParsedMrz p = MrzParser.parseTD3(l1, l2);
                    if (p != null) return p;
                } catch (Exception ex) { }
                try {
                    ParsedMrz p2 = MrzParser.parseTD3Relaxed(l1, l2);
                    if (p2 != null) return p2;
                } catch (Exception ex) { }
                try {
                    ParsedMrz p3 = MrzParser.parseTD3(padToLength(b, 44), padToLength(a, 44));
                    if (p3 != null) return p3;
                } catch (Exception ex) { }
                try {
                    ParsedMrz p4 = MrzParser.parseTD3Relaxed(padToLength(b, 44), padToLength(a, 44));
                    if (p4 != null) return p4;
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

    private boolean looksLikeMrzLine(String s) {
        if (s == null) return false;
        int len = s.length();
        int chevrons = 0;
        for (int i = 0; i < len; i++) if (s.charAt(i) == '<') chevrons++;
        // MRZ lines usually contain many '<' as fillers; require at least 20% '<'
        return chevrons >= Math.max(3, len / 5);
    }

    // ---------- Heuristic corrections (mạnh hơn) ----------
    // Thử nhiều mapping khác nhau và cả tổ hợp 2 mapping
    private ParsedMrz tryHeuristicCorrectionsTD3(List<OcrLine> lines) {
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
            ParsedMrz p = findAndParseMrzStringsTD3(mapped);
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
                ParsedMrz p = findAndParseMrzStringsTD3(mapped);
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
        ParsedMrz p = findAndParseMrzStringsTD3(aggressive);
        if (p != null) {
            Log.d("MRZ_CORRECTION", "Aggressive mapping worked");
            return p;
        }

        return null;
    }

    private ParsedMrz findAndParseMrzStringsTD3(List<String> norms) {
        // chuyển về dạng OcrLine tạm để tận dụng findAndParseMrzTD3Only
        List<OcrLine> tmp = new ArrayList<>();
        for (String s : norms) tmp.add(new OcrLine(s, s, 0));
        return findAndParseMrzTD3Only(tmp);
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

    // Gom các dòng OCR thành 2 hàng MRZ: dùng trung vị Y, ghép từ trái sang phải
    private List<String> buildRowCandidates(List<OcrLine> lines) {
        if (lines == null || lines.isEmpty()) return new ArrayList<>();
        // chỉ giữ các dòng có đủ độ dài hoặc chứa nhiều '<'
        List<OcrLine> candidates = new ArrayList<>();
        for (OcrLine l : lines) {
            if (l.norm.length() >= 12 || looksLikeMrzLine(l.norm)) {
                candidates.add(l);
            }
        }
        if (candidates.isEmpty()) return new ArrayList<>();

        // sắp theo Y
        Collections.sort(candidates, Comparator.comparingDouble(o -> o.centerY));
        // tính khoảng cách giữa các dòng liên tiếp theo Y để phân 2 cụm
        if (candidates.size() <= 2) {
            // ghép thẳng từng dòng theo left
            Collections.sort(candidates, Comparator.comparingDouble(o -> o.left));
            List<String> r = new ArrayList<>();
            for (OcrLine l : candidates) r.add(l.norm);
            return r;
        }

        // tìm ngưỡng phân cụm theo Y: khoảng trống lớn nhất
        int splitIdx = -1;
        float maxGap = -1f;
        for (int i = 0; i < candidates.size() - 1; i++) {
            float gap = candidates.get(i + 1).centerY - candidates.get(i).centerY;
            if (gap > maxGap) { maxGap = gap; splitIdx = i; }
        }

        List<OcrLine> top = new ArrayList<>(candidates.subList(0, splitIdx + 1));
        List<OcrLine> bottom = new ArrayList<>(candidates.subList(splitIdx + 1, candidates.size()));
        Collections.sort(top, Comparator.comparingDouble(o -> o.left));
        Collections.sort(bottom, Comparator.comparingDouble(o -> o.left));

        StringBuilder row1 = new StringBuilder();
        for (OcrLine l : top) row1.append(l.norm);
        StringBuilder row2 = new StringBuilder();
        for (OcrLine l : bottom) row2.append(l.norm);

        List<String> out = new ArrayList<>();
        out.add(row1.toString());
        out.add(row2.toString());
        return out;
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
        lastCandidate = null;
        stableHits = 0;
        statusText.setText("Đặt MRZ của hộ chiếu vào khung màu vàng");
        statusText.setBackgroundColor(0x99000000);
        statusText.setOnClickListener(null);
        mrzFrame.setBackgroundResource(R.drawable.mrz_frame);
        Toast.makeText(this, "Bắt đầu quét lại...", Toast.LENGTH_SHORT).show();
        updateFocusMetering();
    }

    // ---------- helpers & small classes ----------
    private static class OcrLine {
        public final String raw;
        public final String norm;
        public final float centerY;
        public final float left;
        public final float height;
        public OcrLine(String raw, String norm, float centerY, float left, float height) {
            this.raw = raw; this.norm = norm; this.centerY = centerY; this.left = left; this.height = height;
        }
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

    // ---------- Camera controls: AF/AE on MRZ region & torch ----------
    private void updateFocusMetering() {
        if (cameraControl == null) return;
        if (previewView.getWidth() == 0 || previewView.getHeight() == 0) return;

        try {
            MeteringPointFactory factory = previewView.getMeteringPointFactory();
            if (factory == null) return;

            float cx = (mrzFrame.getX() + mrzFrame.getWidth() / 2f) / (float) previewView.getWidth();
            float cy = (mrzFrame.getY() + mrzFrame.getHeight() / 2f) / (float) previewView.getHeight();

            MeteringPoint afPoint = factory.createPoint(cx * previewView.getWidth(), cy * previewView.getHeight());
            FocusMeteringAction action = new FocusMeteringAction.Builder(afPoint,
                    FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE)
                    .setAutoCancelDuration(3, TimeUnit.SECONDS)
                    .build();
            cameraControl.startFocusAndMetering(action);
        } catch (Throwable t) {
            Log.w("MRZ", "Focus/metering not supported: " + t.getMessage());
        }
    }

    private void setupTorchUi() {
        if (torchToggle == null) return;
        torchToggle.setOnClickListener(v -> toggleTorch());
        if (cameraInfo != null) {
            boolean hasFlash = cameraInfo.hasFlashUnit();
            torchToggle.setVisibility(hasFlash ? View.VISIBLE : View.GONE);
        }
    }

    private boolean torchOn = false;
    private void toggleTorch() {
        if (cameraControl == null || cameraInfo == null) return;
        if (!cameraInfo.hasFlashUnit()) return;
        try {
            torchOn = !torchOn;
            cameraControl.enableTorch(torchOn);
        } catch (Throwable t) {
            Log.w("MRZ", "Torch toggle failed: " + t.getMessage());
        }
    }
}
