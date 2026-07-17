from sqlalchemy.sql import func
import secrets
import os
import json
from typing import List
from fastapi import APIRouter, Depends, HTTPException, UploadFile, File, Form
from fastapi.security import OAuth2PasswordRequestForm
from sqlalchemy.orm import Session
from app.database import SessionLocal
from app.models import ChildDevice
from app import auth, models, schemas


router = APIRouter()

def db_session():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()

# --- CHILD DEVICE ENDPOINTS ---

@router.post("/devices/register")
def register_device(name: str, db: Session = Depends(db_session)):
    token = secrets.token_urlsafe(32)
    device = ChildDevice(name=name, device_token=token)
    db.add(device)
    db.commit()
    db.refresh(device)
    return {"device_id": device.id, "device_token": token}

@router.get("/devices")
def list_devices(db: Session = Depends(db_session)):
    return db.query(ChildDevice).all()

@router.get("/devices/{device_id}/policy")
def get_policy(
    device_id: int,
    token: str,
    lat: float = None,
    lng: float = None,
    db: Session = Depends(db_session)
):
    device = db.query(ChildDevice).filter_by(id=device_id, device_token=token).first()
    if not device:
        raise HTTPException(status_code=403, detail="Invalid device token")
    
    # Update last seen timestamp
    device.last_seen = func.now()
    
    # Update location if provided
    if lat is not None and lng is not None:
        device.latitude = lat
        device.longitude = lng
        device.location_updated = func.now()
        
    db.commit()
    
    blocked_apps = db.query(models.InstalledApp).filter_by(device_id=device_id, is_blocked=True).all()
    blocked_packages = [app.package_name for app in blocked_apps]
    
    # Fetch app limits
    limits = db.query(models.AppLimit).filter_by(device_id=device_id).all()
    app_limits = [{"package_name": lim.package_name, "limit_minutes": lim.daily_limit_minutes} for lim in limits]
    
    # Read latest APK update details
    latest_apk_version = None
    latest_apk_url = None
    version_file = "app/static/updates/version.json"
    if os.path.exists(version_file):
        try:
            with open(version_file, "r") as f:
                ver_info = json.load(f)
                latest_apk_version = ver_info.get("version_code")
                latest_apk_url = ver_info.get("apk_url")
        except Exception:
            pass
            
    return {
        "locked": device.locked,
        "lock_reason": device.lock_reason,
        "blocked_packages": blocked_packages,
        "schedule_active": device.schedule_active,
        "schedule_start": device.schedule_start,
        "schedule_end": device.schedule_end,
        "latest_apk_version": latest_apk_version,
        "latest_apk_url": latest_apk_url,
        "web_filter_active": device.web_filter_active,
        "app_limits": app_limits,
        "school_active": device.school_active,
        "school_start": device.school_start,
        "school_end": device.school_end
    }


@router.post("/devices/{device_id}/lock")
def lock_device(device_id: int, db: Session = Depends(db_session)):
    device = db.query(ChildDevice).filter_by(id=device_id).first()
    if not device:
        raise HTTPException(status_code=404, detail="Device not found")
    device.locked = True
    db.commit()
    return {"ok": True, "locked": True}

@router.post("/devices/{device_id}/unlock")
def unlock_device(device_id: int, db: Session = Depends(db_session)):
    device = db.query(ChildDevice).filter_by(id=device_id).first()
    if not device:
        raise HTTPException(status_code=404, detail="Device not found")
    device.locked = False
    db.commit()
    return {"ok": True, "locked": False}


# --- PARENT DASHBOARD ENDPOINTS ---

@router.post("/parent/register", response_model=schemas.UserResponse)
def register_parent(parent_in: schemas.UserCreate, db: Session = Depends(db_session)):
    db_parent = db.query(models.User).filter(models.User.email == parent_in.email).first()
    if db_parent:
        raise HTTPException(status_code=400, detail="Parent already registered")
    
    hashed_pw = auth.get_password_hash(parent_in.password)
    new_parent = models.User(
        email=parent_in.email,
        hashed_password=hashed_pw,
        is_active=True
    )
    db.add(new_parent)
    db.commit()
    db.refresh(new_parent)
    return new_parent

@router.post("/parent/token", response_model=schemas.Token)
def login_parent(
    form_data: OAuth2PasswordRequestForm = Depends(),
    db: Session = Depends(db_session)
):
    user = db.query(models.User).filter(models.User.email == form_data.username).first()
    if not user or not auth.verify_password(form_data.password, user.hashed_password):
        raise HTTPException(
            status_code=401,
            detail="Incorrect email or password",
            headers={"WWW-Authenticate": "Bearer"},
        )
    
    access_token = auth.create_access_token(data={"sub": user.email})
    return {"access_token": access_token, "token_type": "bearer"}

@router.post("/parent/devices", response_model=dict)
def parent_create_device(
    name: str,
    db: Session = Depends(db_session),
    current_user: models.User = Depends(auth.get_current_user)
):
    token = secrets.token_urlsafe(32)
    device = ChildDevice(name=name, device_token=token)
    db.add(device)
    db.commit()
    db.refresh(device)
    return {"device_id": device.id, "device_token": token, "name": device.name}

@router.get("/parent/devices")
def parent_list_devices(
    db: Session = Depends(db_session),
    current_user: models.User = Depends(auth.get_current_user)
):
    devices = db.query(ChildDevice).all()
    result = []
    for d in devices:
        apps = db.query(models.InstalledApp).filter_by(device_id=d.id).order_by(models.InstalledApp.usage_minutes.desc()).all()
        result.append({
            "id": d.id,
            "name": d.name,
            "device_token": d.device_token,
            "locked": d.locked,
            "last_seen": d.last_seen.isoformat() if d.last_seen else None,
            "lock_reason": d.lock_reason,
            "schedule_active": d.schedule_active,
            "schedule_start": d.schedule_start,
            "schedule_end": d.schedule_end,
            "latitude": d.latitude,
            "longitude": d.longitude,
            "location_updated": d.location_updated.isoformat() if d.location_updated else None,
            "web_filter_active": d.web_filter_active,
            "school_active": d.school_active,
            "school_start": d.school_start,
            "school_end": d.school_end,
            "today_usage_minutes": d.today_usage_minutes,
            "apps": [{
                "package_name": app.package_name,
                "app_name": app.app_name,
                "is_blocked": app.is_blocked,
                "usage_minutes": app.usage_minutes
            } for app in apps]
        })
    return result

@router.delete("/parent/devices/{device_id}")
def parent_delete_device(
    device_id: int,
    db: Session = Depends(db_session),
    current_user: models.User = Depends(auth.get_current_user)
):
    device = db.query(ChildDevice).filter_by(id=device_id).first()
    if not device:
        raise HTTPException(status_code=404, detail="Device not found")
    db.delete(device)
    db.commit()
    return {"ok": True}

@router.delete("/parent/account")
def parent_delete_account(
    db: Session = Depends(db_session),
    current_user: models.User = Depends(auth.get_current_user)
):
    db.delete(current_user)
    db.commit()
    return {"ok": True}

@router.post("/parent/devices/{device_id}/lock")
def parent_lock_device(
    device_id: int,
    reason: str = "Das Gerät wurde gesperrt.",
    db: Session = Depends(db_session),
    current_user: models.User = Depends(auth.get_current_user)
):
    device = db.query(ChildDevice).filter_by(id=device_id).first()
    if not device:
        raise HTTPException(status_code=404, detail="Device not found")
    device.locked = True
    device.lock_reason = reason
    db.commit()
    return {"ok": True, "locked": True, "lock_reason": reason}

@router.post("/parent/devices/{device_id}/unlock")
def parent_unlock_device(
    device_id: int,
    db: Session = Depends(db_session),
    current_user: models.User = Depends(auth.get_current_user)
):
    device = db.query(ChildDevice).filter_by(id=device_id).first()
    if not device:
        raise HTTPException(status_code=404, detail="Device not found")
    device.locked = False
    db.commit()
    return {"ok": True, "locked": False}

@router.post("/parent/devices/{device_id}/schedule")
def parent_set_device_schedule(
    device_id: int,
    schedule_active: bool,
    schedule_start: str = "21:00",
    schedule_end: str = "07:00",
    db: Session = Depends(db_session),
    current_user: models.User = Depends(auth.get_current_user)
):
    device = db.query(ChildDevice).filter_by(id=device_id).first()
    if not device:
        raise HTTPException(status_code=404, detail="Device not found")
    device.schedule_active = schedule_active
    device.schedule_start = schedule_start
    device.schedule_end = schedule_end
    db.commit()
    return {
        "ok": True,
        "schedule_active": schedule_active,
        "schedule_start": schedule_start,
        "schedule_end": schedule_end
    }

@router.post("/parent/devices/{device_id}/school")
def parent_set_device_school(
    device_id: int,
    school_active: bool,
    school_start: str = "08:00",
    school_end: str = "13:00",
    db: Session = Depends(db_session),
    current_user: models.User = Depends(auth.get_current_user)
):
    device = db.query(ChildDevice).filter_by(id=device_id).first()
    if not device:
        raise HTTPException(status_code=404, detail="Device not found")
    device.school_active = school_active
    device.school_start = school_start
    device.school_end = school_end
    db.commit()
    return {
        "ok": True,
        "school_active": school_active,
        "school_start": school_start,
        "school_end": school_end
    }

# --- APP MANAGEMENT ENDPOINTS ---

@router.post("/devices/{device_id}/apps")
def upload_installed_apps(
    device_id: int,
    token: str,
    apps_in: List[schemas.AppInfoCreate],
    db: Session = Depends(db_session)
):
    device = db.query(models.ChildDevice).filter_by(id=device_id, device_token=token).first()
    if not device:
        raise HTTPException(status_code=403, detail="Invalid device token")
    
    db_apps = db.query(models.InstalledApp).filter_by(device_id=device_id).all()
    db_apps_map = {app.package_name: app for app in db_apps}
    
    incoming_packages = set()
    for app_in in apps_in:
        incoming_packages.add(app_in.package_name)
        if app_in.package_name in db_apps_map:
            app = db_apps_map[app_in.package_name]
            app.app_name = app_in.app_name
            app.usage_minutes = app_in.usage_minutes or 0
        else:
            new_app = models.InstalledApp(
                device_id=device_id,
                package_name=app_in.package_name,
                app_name=app_in.app_name,
                is_blocked=False,
                usage_minutes=app_in.usage_minutes or 0
            )
            db.add(new_app)
            
    for pkg_name, db_app in db_apps_map.items():
        if pkg_name not in incoming_packages:
            db.delete(db_app)
            
    # Calculate sum of all usage minutes and save to device
    total_usage = sum(app_in.usage_minutes or 0 for app_in in apps_in)
    device.today_usage_minutes = total_usage
            
    db.commit()
    return {"ok": True, "count": len(apps_in)}

@router.get("/parent/devices/{device_id}/apps", response_model=List[schemas.AppInfoResponse])
def parent_get_device_apps(
    device_id: int,
    db: Session = Depends(db_session),
    current_user: models.User = Depends(auth.get_current_user)
):
    device = db.query(models.ChildDevice).filter_by(id=device_id).first()
    if not device:
        raise HTTPException(status_code=404, detail="Device not found")
    
    return db.query(models.InstalledApp).filter_by(device_id=device_id).order_by(models.InstalledApp.app_name).all()

@router.post("/parent/devices/{device_id}/apps/toggle")
def parent_toggle_app_block(
    device_id: int,
    package_name: str,
    is_blocked: bool,
    db: Session = Depends(db_session),
    current_user: models.User = Depends(auth.get_current_user)
):
    app = db.query(models.InstalledApp).filter_by(device_id=device_id, package_name=package_name).first()
    if not app:
        raise HTTPException(status_code=404, detail="App not found on device")
    
    app.is_blocked = is_blocked
    db.commit()
    return {"ok": True, "package_name": package_name, "is_blocked": is_blocked}

# --- WEB FILTER ENDPOINTS ---

@router.post("/parent/devices/{device_id}/webfilter")
def parent_toggle_webfilter(
    device_id: int,
    web_filter_active: bool,
    db: Session = Depends(db_session),
    current_user: models.User = Depends(auth.get_current_user)
):
    device = db.query(ChildDevice).filter_by(id=device_id).first()
    if not device:
        raise HTTPException(status_code=404, detail="Device not found")
    device.web_filter_active = web_filter_active
    db.commit()
    return {"ok": True, "device_id": device_id, "web_filter_active": web_filter_active}

# --- APP LIMITS ENDPOINTS ---

@router.get("/parent/devices/{device_id}/limits", response_model=List[schemas.AppLimitResponse])
def parent_get_limits(
    device_id: int,
    db: Session = Depends(db_session),
    current_user: models.User = Depends(auth.get_current_user)
):
    device = db.query(ChildDevice).filter_by(id=device_id).first()
    if not device:
        raise HTTPException(status_code=404, detail="Device not found")
    return db.query(models.AppLimit).filter_by(device_id=device_id).all()

@router.post("/parent/devices/{device_id}/limits")
def parent_set_limit(
    device_id: int,
    limit_in: schemas.AppLimitCreate,
    db: Session = Depends(db_session),
    current_user: models.User = Depends(auth.get_current_user)
):
    device = db.query(ChildDevice).filter_by(id=device_id).first()
    if not device:
        raise HTTPException(status_code=404, detail="Device not found")
    
    limit = db.query(models.AppLimit).filter_by(device_id=device_id, package_name=limit_in.package_name).first()
    if limit:
        limit.daily_limit_minutes = limit_in.daily_limit_minutes
    else:
        limit = models.AppLimit(
            device_id=device_id,
            package_name=limit_in.package_name,
            daily_limit_minutes=limit_in.daily_limit_minutes
        )
        db.add(limit)
    db.commit()
    db.refresh(limit)
    return limit

@router.post("/parent/devices/{device_id}/limits/delete")
def parent_delete_limit(
    device_id: int,
    package_name: str,
    db: Session = Depends(db_session),
    current_user: models.User = Depends(auth.get_current_user)
):
    limit = db.query(models.AppLimit).filter_by(device_id=device_id, package_name=package_name).first()
    if not limit:
        raise HTTPException(status_code=404, detail="Limit not found")
    db.delete(limit)
    db.commit()
    return {"ok": True}

# --- OTA UPDATE ENDPOINT ---

UPDATE_DIR = "app/static/updates"

@router.post("/parent/upload-apk")
async def parent_upload_apk(
    version_code: int = Form(...),
    version_name: str = Form(...),
    file: UploadFile = File(...),
    db: Session = Depends(db_session),
    current_user: models.User = Depends(auth.get_current_user)
):
    os.makedirs(UPDATE_DIR, exist_ok=True)
    file_path = os.path.join(UPDATE_DIR, "rieseguard-child.apk")
    
    with open(file_path, "wb") as f:
        f.write(await file.read())
        
    update_info = {
        "version_code": version_code,
        "version_name": version_name,
        "apk_url": "/updates/rieseguard-child.apk"
    }
    
    with open(os.path.join(UPDATE_DIR, "version.json"), "w") as f:
        json.dump(update_info, f)
        
    return {"ok": True, "version_code": version_code, "version_name": version_name}

