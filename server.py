import os
import cv2
import numpy as np
import onnxruntime as ort
import sqlite3
from fastapi import FastAPI, File, UploadFile, Form, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from typing import List

app = FastAPI(title="Face Attendance System API", version="1.0")

# Enable CORS for all connections
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# ── CONFIGURATION & MODEL PATHS ──────────────────────────────────────────────
ASSETS_DIR = "app/src/main/assets"
DET_MODEL = os.path.join(ASSETS_DIR, "det_500m.onnx")
REC_MODEL = os.path.join(ASSETS_DIR, "w600k_mbf.onnx")
DB_FILE = "server_faces.db"

# Initialize SQLite database
def init_db():
    conn = sqlite3.connect(DB_FILE)
    c = conn.cursor()
    c.execute('''
        CREATE TABLE IF NOT EXISTS students (
            student_id TEXT PRIMARY KEY,
            name TEXT NOT NULL,
            roll_number TEXT,
            class_name TEXT,
            embedding BLOB NOT NULL
        )
    ''')
    conn.commit()
    conn.close()

init_db()

# Load ONNX sessions
print("Loading face detection and recognition models...")
det_session = ort.InferenceSession(DET_MODEL, providers=['CPUExecutionProvider'])
rec_session = ort.InferenceSession(REC_MODEL, providers=['CPUExecutionProvider'])
print("Models loaded successfully!")

# ── SCRFD DETECTOR DECODING UTILS ────────────────────────────────────────────
STRIDES = [8, 16, 32]
NUM_ANCHORS = 2

def generate_anchor_centers(grid_h, grid_w, stride):
    centers = []
    for row in range(grid_h):
        for col in range(grid_w):
            cx, cy = col * stride, row * stride
            for _ in range(NUM_ANCHORS):
                centers.append([cx, cy])
    return np.array(centers, dtype=np.float32)

def nms(bboxes, scores, threshold=0.4):
    if len(bboxes) == 0:
        return []
    x1 = bboxes[:, 0]
    y1 = bboxes[:, 1]
    x2 = bboxes[:, 2]
    y2 = bboxes[:, 3]
    areas = (x2 - x1) * (y2 - y1)
    order = scores.argsort()[::-1]
    keep = []
    while order.size > 0:
        i = order[0]
        keep.append(i)
        xx1 = np.maximum(x1[i], x1[order[1:]])
        yy1 = np.maximum(y1[i], y1[order[1:]])
        xx2 = np.minimum(x2[i], x2[order[1:]])
        yy2 = np.minimum(y2[i], y2[order[1:]])
        w = np.maximum(0.0, xx2 - xx1)
        h = np.maximum(0.0, yy2 - yy1)
        inter = w * h
        ovr = inter / (areas[i] + areas[order[1:]] - inter + 1e-6)
        inds = np.where(ovr <= threshold)[0]
        order = order[inds + 1]
    return keep

def detect_faces(img, conf_thresh=0.30):
    h, w, _ = img.shape
    
    # Resize and pad to 960x960 (dynamic high resolution grid)
    det_size = 960
    scale = det_size / max(h, w)
    new_h, new_w = int(h * scale), int(w * scale)
    
    # Convert input to RGB (model expects RGB channel order)
    rgb = cv2.cvtColor(img, cv2.COLOR_BGR2RGB)
    resized = cv2.resize(rgb, (new_w, new_h))
    
    det_input = np.zeros((det_size, det_size, 3), dtype=np.uint8)
    det_input[:new_h, :new_w, :] = resized
    
    # Preprocess (RGB layout, normalize: value - 127.5 / 128.0)
    blob = (det_input.astype(np.float32) - 127.5) / 128.0
    blob = np.transpose(blob, (2, 0, 1)) # HWC -> CHW
    blob = np.expand_dims(blob, axis=0)  # CHW -> NCHW
    
    # Run SCRFD
    outputs = det_session.run(None, {det_session.get_inputs()[0].name: blob})
    
    all_bboxes = []
    all_scores = []
    
    fmc = len(STRIDES) # 3
    for i in range(fmc):
        stride = STRIDES[i]
        scores = outputs[i].flatten()
        bboxes = outputs[i + fmc].reshape(-1, 4)
        
        grid_h, grid_w = det_size // stride, det_size // stride
        centers = generate_anchor_centers(grid_h, grid_w, stride)
        
        # Filter by threshold
        valid_idx = np.where(scores >= conf_thresh)[0]
        for idx in valid_idx:
            score = scores[idx]
            cx, cy = centers[idx]
            
            x1 = (cx - bboxes[idx, 0] * stride) / scale
            y1 = (cy - bboxes[idx, 1] * stride) / scale
            x2 = (cx + bboxes[idx, 2] * stride) / scale
            y2 = (cy + bboxes[idx, 3] * stride) / scale
            
            # Clamp to original image bounds
            x1 = max(0.0, min(x1, w))
            y1 = max(0.0, min(y1, h))
            x2 = max(0.0, min(x2, w))
            y2 = max(0.0, min(y2, h))
            
            all_bboxes.append([x1, y1, x2, y2])
            all_scores.append(score)
            
    all_bboxes = np.array(all_bboxes)
    all_scores = np.array(all_scores)
    
    keep = nms(all_bboxes, all_scores, threshold=0.4)
    return [{"bbox": all_bboxes[k].tolist(), "score": float(all_scores[k])} for k in keep]

# ── ARCFACE EMBEDDING UTILS ──────────────────────────────────────────────────
def get_face_embedding(face_crop):
    # Convert BGR to RGB (ArcFace expects RGB input)
    rgb = cv2.cvtColor(face_crop, cv2.COLOR_BGR2RGB)
    
    # Resize to canonical size (112x112)
    resized = cv2.resize(rgb, (112, 112))
    
    # Normalize: (pixel - 127.5) / 127.5
    blob = (resized.astype(np.float32) - 127.5) / 127.5
    blob = np.transpose(blob, (2, 0, 1)) # HWC -> CHW
    blob = np.expand_dims(blob, axis=0)  # CHW -> NCHW
    
    # Run ArcFace
    out = rec_session.run(None, {rec_session.get_inputs()[0].name: blob})[0]
    embedding = out[0]
    
    # L2-normalize
    norm = np.linalg.norm(embedding)
    if norm < 1e-10:
        return embedding
    return embedding / norm

# ── ENDPOINTS ────────────────────────────────────────────────────────────────

@app.post("/register")
async def register_student(
    student_id: str = Form(...),
    name: str = Form(...),
    roll_number: str = Form(None),
    class_name: str = Form(None),
    file: UploadFile = File(...)
):
    try:
        contents = await file.read()
        nparr = np.frombuffer(contents, np.uint8)
        img = cv2.imdecode(nparr, cv2.IMREAD_COLOR) # BGR
        
        if img is None:
            raise HTTPException(status_code=400, detail="Invalid image file")
            
        # Debug: save the registered face crop
        cv2.imwrite("debug_registered_face.jpg", img)
            
        # Extract embedding
        embedding = get_face_embedding(img)
        emb_bytes = embedding.tobytes()
        
        # Save to SQLite
        conn = sqlite3.connect(DB_FILE)
        c = conn.cursor()
        c.execute('''
            INSERT OR REPLACE INTO students (student_id, name, roll_number, class_name, embedding)
            VALUES (?, ?, ?, ?, ?)
        ''', (student_id, name, roll_number, class_name, emb_bytes))
        conn.commit()
        conn.close()
        
        return {"status": "success", "message": f"Student '{name}' registered successfully!"}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/recognize")
async def recognize_group_faces(
    file: UploadFile = File(...),
    threshold: float = Form(0.30)
):
    try:
        contents = await file.read()
        nparr = np.frombuffer(contents, np.uint8)
        img = cv2.imdecode(nparr, cv2.IMREAD_COLOR) # BGR
        
        if img is None:
            raise HTTPException(status_code=400, detail="Invalid image file")
            
        # Detect faces
        faces = detect_faces(img, conf_thresh=0.30)
        
        # Load registered students
        conn = sqlite3.connect(DB_FILE)
        c = conn.cursor()
        c.execute("SELECT student_id, name, roll_number, class_name, embedding FROM students")
        rows = c.fetchall()
        conn.close()
        
        registered = []
        for row in rows:
            emb = np.frombuffer(row[4], dtype=np.float32)
            registered.append({
                "student_id": row[0],
                "name": row[1],
                "roll_number": row[2],
                "class_name": row[3],
                "embedding": emb
            })
            
        results = []
        
        for face in faces:
            x1, y1, x2, y2 = [int(val) for val in face["bbox"]]
            
            # Padded crop for ArcFace
            pad_w = int((x2 - x1) * 0.20)
            pad_h = int((y2 - y1) * 0.20)
            l = max(0, x1 - pad_w)
            t = max(0, y1 - pad_h)
            r = min(img.shape[1], x2 + pad_w)
            b = min(img.shape[0], y2 + pad_h)
            
            crop = img[t:b, l:r]
            if crop.size == 0:
                continue
                
            # Debug: save the recognized face crop
            cv2.imwrite("debug_recognized_face.jpg", crop)
                
            probe = get_face_embedding(crop)
            
            # Find best match using cosine similarity
            best_student = None
            best_sim = -1.0
            
            for student in registered:
                sim = float(np.dot(probe, student["embedding"]))
                if sim > best_sim:
                    best_sim = sim
                    best_student = student
            
            is_recognized = best_sim >= threshold
            
            results.append({
                "bbox": [x1, y1, x2, y2],
                "is_recognized": is_recognized,
                "name": best_student["name"] if (is_recognized and best_student) else "Unknown Face",
                "student_id": best_student["student_id"] if (is_recognized and best_student) else None,
                "roll_number": best_student["roll_number"] if (is_recognized and best_student) else None,
                "class_name": best_student["class_name"] if (is_recognized and best_student) else None,
                "similarity": best_sim
            })
            
        return {"status": "success", "detected_count": len(faces), "matches": results}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/clear_db")
async def clear_database():
    try:
        conn = sqlite3.connect(DB_FILE)
        c = conn.cursor()
        c.execute("DELETE FROM students")
        conn.commit()
        conn.close()
        return {"status": "success", "message": "Database wiped successfully!"}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
