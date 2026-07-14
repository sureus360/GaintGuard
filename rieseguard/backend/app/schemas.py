from pydantic import BaseModel
from typing import Optional

class UserBase(BaseModel):
    email: str

class UserCreate(UserBase):
    password: str

class UserResponse(UserBase):
    id: int
    is_active: bool

    class Config:
        from_attributes = True

class Token(BaseModel):
    access_token: str
    token_type: str

class TokenData(BaseModel):
    email: Optional[str] = None

class AppInfoBase(BaseModel):
    package_name: str
    app_name: str

class AppInfoCreate(AppInfoBase):
    pass

class AppInfoResponse(AppInfoBase):
    id: int
    device_id: int
    is_blocked: bool

    class Config:
        from_attributes = True
