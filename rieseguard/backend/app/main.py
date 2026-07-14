from fastapi import FastAPI
from fastapi.staticfiles import StaticFiles
from app.routes import router
from app.database import Base, engine

Base.metadata.create_all(bind=engine)

app = FastAPI(title="RieseGuard API", version="0.1.0")
app.include_router(router)

# Serve the parent dashboard HTML/JS/CSS frontend
app.mount("/", StaticFiles(directory="app/static", html=True), name="static")
