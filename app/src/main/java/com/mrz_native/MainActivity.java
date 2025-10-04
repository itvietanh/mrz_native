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
import android.view.WindowManager;

public class MainActivity extends AppCompatActivity {
    private static final int REQ_CODE = 101;
    private static final long OCR_FRAME_INTERVAL_MS = 100; // throttle OCR slightly faster
    private static final int REQUIRED_STABLE_HITS = 2; // frames to confirm
    private PreviewView previewView;
    private TextView statusText;
    private TextView ocrDebugText;
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
    private boolean restrictToRoi = true;  // filter OCR lines to the overlay region

    // Keep last known image rotation and rotated dimensions for ROI mapping
    private volatile int lastRotationDegrees = 0;
    private volatile int lastRotatedW = 0;
    private volatile int lastRotatedH = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        // Keep screen on during scanning session
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        previewView = findViewById(R.id.previewView);
        // Ensure mapping math matches how the camera is rendered on screen
        previewView.setScaleType(PreviewView.ScaleType.FIT_CENTER);
        statusText = findViewById(R.id.statusText);
        ocrDebugText = findViewById(R.id.ocrDebugText);
        mrzFrame = findViewById(R.id.mrz_guide_frame);
        torchToggle = findViewById(R.id.torchToggle);
        cameraExecutor = Executors.newSingleThreadExecutor();

        if (ocrDebugText != null) {
            // Allow scrolling through debug lines if many
            try {
                ocrDebugText.setMovementMethod(android.text.method.ScrollingMovementMethod.getInstance());
            } catch (Throwable ignore) {}
        }

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
                // Keep refocusing while scanning to improve sharpness
                previewView.postDelayed(focusRepeater, 1500);
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

        // Cache rotation and rotated dimensions for consistent ROI mapping later
        lastRotationDegrees = imageProxy.getImageInfo().getRotationDegrees();
        int imgW = imageProxy.getWidth();
        int imgH = imageProxy.getHeight();
        if (lastRotationDegrees % 180 == 0) {
            lastRotatedW = imgW;
            lastRotatedH = imgH;
        } else {
            lastRotatedW = imgH;
            lastRotatedH = imgW;
        }

        // Optional: crop to MRZ guide region when mapping is verified
        if (enableRoiCrop) {
            Rect cropRect = computeCropRectForImageProxy(imageProxy);
            if (cropRect != null) imageProxy.setCropRect(cropRect);
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
        // Tính ROI trong hệ toạ độ ảnh đã xoay (phù hợp với bounding boxes MLKit)
        Rect roiRotated = computeRoiRectInRotatedSpace();

        // Thu thập các dòng và phân loại theo ROI
        List<OcrLine> inside = new ArrayList<>();
        List<OcrLine> outside = new ArrayList<>();
        StringBuilder dbg = new StringBuilder();

        for (Text.TextBlock block : visionText.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                String raw = line.getText();
                if (raw == null) continue;
                String norm = normalizeLine(raw);
                if (norm.isEmpty()) continue;
                Rect bb = line.getBoundingBox();
                boolean in = false;
                float cy = 0f;
                if (bb != null) {
                    int cx = bb.centerX();
                    int cY = bb.centerY();
                    cy = cY;
                    if (roiRotated != null) {
                        in = roiRotated.contains(cx, cY);
                    }
                }
                if (!restrictToRoi) in = true; // bypass filter if disabled

                if (in) {
                    inside.add(new OcrLine(raw, norm, cy));
                    if (dbg.length() < 2000) dbg.append("[IN]  ").append(raw).append('\n');
                } else {
                    outside.add(new OcrLine(raw, norm, cy));
                    if (dbg.length() < 2000) dbg.append("[OUT] ").append(raw).append('\n');
                }
            }
        }

        // Hiển thị debug text để kiểm tra có đọc ngoài vùng không
        if (ocrDebugText != null) {
            final String dbgText = dbg.length() == 0 ? "(Không có văn bản)" : dbg.toString();
            runOnUiThread(() -> ocrDebugText.setText(dbgText));
        }

        List<OcrLine> linesForMrz = restrictToRoi ? inside : mergeInsideFirst(inside, outside);

        if (linesForMrz.isEmpty()) {
            showMessageOnUi("Đưa vùng MRZ vào khung vàng", null);
            return;
        }

        // Sắp xếp theo vị trí dọc để tăng khả năng gom đúng dòng MRZ
        Collections.sort(linesForMrz, Comparator.comparingDouble(l -> l.centerY));

        // Thử tìm MRZ theo nhiều cách: TD3 (2x44), TD2 (2x36), TD1 (3x30)
        ParsedMrz parsed = findAndParseMrz(linesForMrz);
        if (parsed != null) {
            onCandidateDetected(parsed, false);
            return;
        }

        // Nếu không parse được trực tiếp, thử các heuristic corrections
        ParsedMrz corrected = tryHeuristicCorrectionsMultiple(linesForMrz);
        if (corrected != null) {
            onCandidateDetected(corrected, true);
            return;
        }

        // Không tìm được
        showMessageOnUi("Đặt MRZ của hộ chiếu vào khung màu vàng", null);
    }

    private List<OcrLine> mergeInsideFirst(List<OcrLine> inside, List<OcrLine> outside) {
        List<OcrLine> out = new ArrayList<>(inside.size() + outside.size());
        out.addAll(inside);
        out.addAll(outside);
        return out;
    }

    // Tính cropRect của ảnh gốc (chưa xoay) từ ROI trên màn hình
    private Rect computeCropRectForImageProxy(ImageProxy imageProxy) {
        if (previewView.getWidth() == 0 || previewView.getHeight() == 0) return null;
        int rotation = imageProxy.getImageInfo().getRotationDegrees();
        int imgW = imageProxy.getWidth();
        int imgH = imageProxy.getHeight();

        // Kích thước ảnh sau khi xoay
        int rotW = (rotation % 180 == 0) ? imgW : imgH;
        int rotH = (rotation % 180 == 0) ? imgH : imgW;

        // Tính ROI trong không gian đã xoay (khớp với hiển thị)
        Rect roiRot = computeRoiRectInRotatedSpace(rotW, rotH);
        if (roiRot == null) return null;

        // Chuyển ROI từ toạ độ đã xoay về toạ độ ảnh gốc trước xoay
        Rect roiRaw = mapRotatedRectToRawImage(roiRot, imgW, imgH, rotation);
        // Đảm bảo nằm trong ảnh
        roiRaw.left = clamp(roiRaw.left, 0, imgW);
        roiRaw.top = clamp(roiRaw.top, 0, imgH);
        roiRaw.right = clamp(roiRaw.right, 0, imgW);
        roiRaw.bottom = clamp(roiRaw.bottom, 0, imgH);
        if (roiRaw.right <= roiRaw.left || roiRaw.bottom <= roiRaw.top) return null;
        return roiRaw;
    }

    // Tính ROI trong hệ ảnh đã xoay dựa trên kích thước cuối cùng hiển thị trong PreviewView (fitCenter)
    private Rect computeRoiRectInRotatedSpace() {
        if (lastRotatedW <= 0 || lastRotatedH <= 0) return null;
        return computeRoiRectInRotatedSpace(lastRotatedW, lastRotatedH);
    }

    private Rect computeRoiRectInRotatedSpace(int rotW, int rotH) {
        int viewW = previewView.getWidth();
        int viewH = previewView.getHeight();
        if (viewW == 0 || viewH == 0) return null;

        // FitCenter: tính kích thước ảnh hiển thị trong view và offset letterbox
        float imgAspect = (float) rotW / (float) rotH;
        float viewAspect = (float) viewW / (float) viewH;
        float scaledW, scaledH;
        if (imgAspect > viewAspect) {
            scaledW = viewW;
            scaledH = viewW / imgAspect;
        } else {
            scaledH = viewH;
            scaledW = viewH * imgAspect;
        }
        float offsetX = (viewW - scaledW) / 2f;
        float offsetY = (viewH - scaledH) / 2f;

        // Toạ độ ROI trên view
        float frameLeft = mrzFrame.getX();
        float frameTop = mrzFrame.getY();
        float frameRight = frameLeft + mrzFrame.getWidth();
        float frameBottom = frameTop + mrzFrame.getHeight();

        // Quy đổi về [0,1] trong không gian ảnh đã fit vào view
        float leftN = (frameLeft - offsetX) / scaledW;
        float topN = (frameTop - offsetY) / scaledH;
        float rightN = (frameRight - offsetX) / scaledW;
        float bottomN = (frameBottom - offsetY) / scaledH;

        // Clamp
        leftN = clamp(leftN, 0f, 1f);
        topN = clamp(topN, 0f, 1f);
        rightN = clamp(rightN, 0f, 1f);
        bottomN = clamp(bottomN, 0f, 1f);

        int L = Math.round(leftN * rotW);
        int T = Math.round(topN * rotH);
        int R = Math.round(rightN * rotW);
        int B = Math.round(bottomN * rotH);
        if (R <= L || B <= T) return null;
        return new Rect(L, T, R, B);
    }

    // Chuyển rect từ toạ độ đã xoay về toạ độ gốc (trước xoay)
    private Rect mapRotatedRectToRawImage(Rect rotRect, int rawW, int rawH, int rotationDegrees) {
        // map 4 góc rồi lấy bound
        float[][] corners = new float[][]{
                {rotRect.left, rotRect.top},
                {rotRect.right, rotRect.top},
                {rotRect.right, rotRect.bottom},
                {rotRect.left, rotRect.bottom}
        };

        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (float[] c : corners) {
            float vx = c[0] / (float) (rotationDegrees % 180 == 0 ? rawW : rawH); // normalized in rotated space width
            float vy = c[1] / (float) (rotationDegrees % 180 == 0 ? rawH : rawW); // normalized in rotated space height
            float[] raw = mapViewNormToRawNorm(vx, vy, rotationDegrees);
            float rx = raw[0] * rawW;
            float ry = raw[1] * rawH;
            if (rx < minX) minX = rx;
            if (ry < minY) minY = ry;
            if (rx > maxX) maxX = rx;
            if (ry > maxY) maxY = ry;
        }
        return new Rect(Math.round(minX), Math.round(minY), Math.round(maxX), Math.round(maxY));
    }

    // Ánh xạ toạ độ view chuẩn hoá (sau xoay) -> toạ độ ảnh gốc chuẩn hoá
    private float[] mapViewNormToRawNorm(float vx, float vy, int rotationDegrees) {
        switch ((rotationDegrees % 360 + 360) % 360) {
            case 0:
                return new float[]{vx, vy};
            case 90:
                // ixN = vy; iyN = 1 - vx
                return new float[]{vy, 1f - vx};
            case 180:
                return new float[]{1f - vx, 1f - vy};
            case 270:
                // ixN = 1 - vy; iyN = vx
                return new float[]{1f - vy, vx};
            default:
                return new float[]{vx, vy};
        }
    }

    private int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
    private float clamp(float v, float min, float max) { return Math.max(min, Math.min(max, v)); }

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

    private ParsedMrz findAndParseMrz(List<OcrLine> lines) {
        // Tạo danh sách chỉ chứa text chuẩn
        List<String> norms = new ArrayList<>();
        for (OcrLine l : lines) {
            String n = l.norm;
            // bộ lọc nhanh: bỏ các dòng quá ngắn hoặc không có '<' (MRZ tiêu chuẩn có nhiều '<')
            if (n.length() < 10) continue;
            norms.add(n);
        }

        // Thử tìm TD1 (3 dòng x ~30)
        for (int i = 0; i + 2 < norms.size(); i++) {
            String a = norms.get(i);
            String b = norms.get(i + 1);
            String c = norms.get(i + 2);
            if (looksLikeMrzLine(a) && looksLikeMrzLine(b) && looksLikeMrzLine(c)
                    && isLengthApprox(a, 30) && isLengthApprox(b, 30) && isLengthApprox(c, 30)) {
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
            if ((isLengthApprox(a, 44) || isLengthApprox(b, 44) || (a.startsWith("P") || a.startsWith("V")))
                    && looksLikeMrzLine(a) && looksLikeMrzLine(b)) {
                String l1 = padToLength(a, 44);
                String l2 = padToLength(b, 44);
                try {
                    ParsedMrz p = MrzParser.parseTD3(l1, l2);
                    if (p != null) return p;
                } catch (Exception ex) { }
            }

            // TD2 candidate (pad to 36)
            if ((isLengthApprox(a, 36) || isLengthApprox(b, 36))
                    && looksLikeMrzLine(a) && looksLikeMrzLine(b)) {
                String l1 = padToLength(a, 36);
                String l2 = padToLength(b, 36);
                try {
                    ParsedMrz p = MrzParser.parseTD2(l1, l2);
                    if (p != null) return p;
                } catch (Exception ex) { }
            }

            // Fallback: try pad 44 even if slightly shorter (some OCR trim)
            if (a.length() >= 20 && b.length() >= 20 && looksLikeMrzLine(a) && looksLikeMrzLine(b)) {
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
        // Khoảng dung sai nới lỏng: -10/+5 ký tự để chịu lỗi OCR
        return len >= target - 10 && len <= target + 5;
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
        // MRZ lines usually contain many '<' as fillers; require at least ~15% '<'
        return chevrons >= Math.max(2, Math.round(len * 0.15f));
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
        for (String s : norms) tmp.add(new OcrLine(s, s, 0f));
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
        public OcrLine(String raw, String norm, float centerY) { this.raw = raw; this.norm = norm; this.centerY = centerY; }
    }

    private static class Pair<F,S> {
        public final F first;
        public final S second;
        public Pair(F f, S s) { first=f; second=s;}
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Stop repeated focusing callbacks
        if (previewView != null) previewView.removeCallbacks(focusRepeater);
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

            // Factory expects normalized [0,1] coordinates
            MeteringPoint afPoint = factory.createPoint(cx, cy);
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
        // initial visual state
        torchToggle.setBackgroundColor(0x66FFFFFF);
    }

    private boolean torchOn = false;
    private void toggleTorch() {
        if (cameraControl == null || cameraInfo == null) return;
        if (!cameraInfo.hasFlashUnit()) return;
        try {
            torchOn = !torchOn;
            cameraControl.enableTorch(torchOn);
            // simple visual feedback
            torchToggle.setBackgroundColor(torchOn ? 0xFFFFC107 : 0x66FFFFFF);
        } catch (Throwable t) {
            Log.w("MRZ", "Torch toggle failed: " + t.getMessage());
        }
    }

    // Repeat focus/metering at interval while scanning
    private final Runnable focusRepeater = new Runnable() {
        @Override public void run() {
            if (isScanning) {
                updateFocusMetering();
                if (previewView != null) previewView.postDelayed(this, 2000);
            }
        }
    };
}
