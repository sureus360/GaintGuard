from sqlalchemy import Column, Integer, String, Boolean, DateTime, ForeignKey, Float
from sqlalchemy.sql import func
from app.database import Base

class User(Base):
    __tablename__ = "users"

    id = Column(Integer, primary_key=True, index=True)
    email = Column(String, unique=True, index=True, nullable=False)
    hashed_password = Column(String, nullable=False)
    is_active = Column(Boolean, default=True)

class ChildDevice(Base):
    __tablename__ = "child_devices"

    id = Column(Integer, primary_key=True, index=True)
    name = Column(String, nullable=False)
    device_token = Column(String, unique=True, index=True, nullable=False)
    locked = Column(Boolean, default=False)
    last_seen = Column(DateTime(timezone=True), server_default=func.now())
    lock_reason = Column(String, default="Das Gerät wurde gesperrt.")
    schedule_active = Column(Boolean, default=False)
    schedule_start = Column(String, default="21:00")
    schedule_end = Column(String, default="07:00")
    
    # New features
    latitude = Column(Float, nullable=True)
    longitude = Column(Float, nullable=True)
    location_updated = Column(DateTime(timezone=True), nullable=True)
    web_filter_active = Column(Boolean, default=False)
    
    # School mode
    school_active = Column(Boolean, default=False)
    school_start = Column(String, default="08:00")
    school_end = Column(String, default="13:00")
    today_usage_minutes = Column(Integer, default=0)

class InstalledApp(Base):
    __tablename__ = "installed_apps"

    id = Column(Integer, primary_key=True, index=True)
    device_id = Column(Integer, ForeignKey("child_devices.id", ondelete="CASCADE"), nullable=False)
    package_name = Column(String, nullable=False)
    app_name = Column(String, nullable=False)
    is_blocked = Column(Boolean, default=False)
    usage_minutes = Column(Integer, default=0)

class AppLimit(Base):
    __tablename__ = "app_limits"

    id = Column(Integer, primary_key=True, index=True)
    device_id = Column(Integer, ForeignKey("child_devices.id", ondelete="CASCADE"), nullable=False)
    package_name = Column(String, nullable=False)
    daily_limit_minutes = Column(Integer, nullable=False)

