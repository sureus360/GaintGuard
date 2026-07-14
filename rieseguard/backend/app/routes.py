from sqlalchemy.sql import func
import secrets
from typing import List
from fastapi import APIRouter, Depends, HTTPException
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
def get_policy(device_id: int, token: str, db: Session = Depends(db_session)):
    device = db.query(ChildDevice).filter_by(id=device_id, device_token=token).first()
    if not device:
        raise HTTPException(status_code=403, detail="Invalid device token")
    
    # Update last seen timestamp
    device.last_seen = func.now()
    db.commit()
    
    blocked_apps = db.query(models.InstalledApp).filter_by(device_id=device_id, is_blocked=True).all()
    blocked_packages = [app.package_name for app in blocked_apps]
    
    return {
        "locked": device.locked,
        "lock_reason": device.lock_reason,
        "blocked_packages": blocked_packages,
        "schedule_active": device.schedule_active,
        "schedule_start": device.schedule_start,
        "schedule_end": device.schedule_end
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
    return db.query(ChildDevice).all()

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
            if app.app_name != app_in.app_name:
                app.app_name = app_in.app_name
        else:
            new_app = models.InstalledApp(
                device_id=device_id,
                package_name=app_in.package_name,
                app_name=app_in.app_name,
                is_blocked=False
            )
            db.add(new_app)
            
    for pkg_name, db_app in db_apps_map.items():
        if pkg_name not in incoming_packages:
            db.delete(db_app)
            
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
