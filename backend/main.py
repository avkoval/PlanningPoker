from typing import Any, List

import starlette.status as status
from authlib.integrations.starlette_client import (  # type: ignore[import]
    OAuth, OAuthError)
from fastapi import FastAPI, Request
from fastapi.responses import FileResponse, HTMLResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from jira import JIRA
from jira.client import ResultList
from jira.exceptions import JIRAError
from jira.resources import Issue
from pydantic import BaseModel
from starlette.config import Config
from starlette.middleware.sessions import SessionMiddleware
from starlette.responses import RedirectResponse

from .config import Settings

settings = Settings()

# OAuth settings
GOOGLE_CLIENT_ID = settings.google_client_id
GOOGLE_CLIENT_SECRET = settings.google_client_secret
if GOOGLE_CLIENT_ID is None or GOOGLE_CLIENT_SECRET is None:
    raise BaseException('Missing env variables')


# Set up oauth
config_data = {'GOOGLE_CLIENT_ID': GOOGLE_CLIENT_ID, 'GOOGLE_CLIENT_SECRET': GOOGLE_CLIENT_SECRET}
starlette_config = Config(environ=config_data)
oauth = OAuth(starlette_config)
oauth.register(
    name='google',
    server_metadata_url='https://accounts.google.com/.well-known/openid-configuration',
    client_kwargs={'scope': 'openid email profile'},
)

app = FastAPI()

SECRET_KEY = settings.secret_key
if SECRET_KEY is None:
    raise Exception('Missing SECRET_KEY')
app.add_middleware(SessionMiddleware, secret_key=SECRET_KEY)

app.mount("/app/", StaticFiles(directory="pp-front/public", html=True), name="app")
app.mount("/static/", StaticFiles(directory="static", html=True), name="static")
app.mount("/js/", StaticFiles(directory="pp-front/public/js/", html=True), name="static")

favicon_path = 'static/favicon.ico'

jira = JIRA(
    options={
        "server": settings.jira_server
    },
    basic_auth=(settings.jira_user_email, settings.jira_token)
)


templates = Jinja2Templates(directory="templates")


@app.get("/", response_class=HTMLResponse)
async def home(request: Request):
    notifications: List = []
    if 'access_token' in request.session:
        domain = request.session['access_token']['userinfo']['email'].split('@')[1]
        allowed_domains = [d for d in settings.allowed_email_domains.split(',') if d != '']
        if len(allowed_domains) and domain not in allowed_domains:
            msg = f"Domain not allowed: {domain} for user {request.session['access_token']['userinfo']['email']}"
            notifications.append({'type': 'danger', 'text': msg})
        else:
            return RedirectResponse('/app/')
    return templates.TemplateResponse(
        name="index.html", context={'request': request, 'notifications': notifications}
    )


@app.get('/favicon.ico', include_in_schema=False)
async def favicon():
    return FileResponse(favicon_path)


@app.route('/login')
async def login(request: Request):
    redirect_uri = request.url_for('auth')  # This creates the url for the /auth endpoint
    return await oauth.google.authorize_redirect(request, redirect_uri)


@app.post('/logout')
async def logout(request: Request):
    if 'access_token' in request.session:
        del request.session['access_token']
    return RedirectResponse('/', status_code=status.HTTP_302_FOUND)


class UserInfo(BaseModel):
    logged_in: bool = False
    email: str | None = None
    family_name: str | None = None
    given_name: str | None = None
    name: str | None = None
    picture: str | None = None


@app.get('/userInfo')
async def user_info(request: Request) -> UserInfo:
    if not request.session.get('access_token'):
        return UserInfo()
    userinfo = request.session['access_token']['userinfo']
    return UserInfo(
        email=userinfo['email'],
        family_name=userinfo['family_name'],
        given_name=userinfo['given_name'],
        name=userinfo['name'],
        picture=userinfo['picture'],
    )


@app.route('/auth')
async def auth(request: Request):
    try:
        access_token = await oauth.google.authorize_access_token(request)
    except OAuthError as e:
        return templates.TemplateResponse(
            name="index.html", context={'request': request, 'error': e}
        )
    request.session['access_token'] = access_token
    return RedirectResponse('/app/')


class FoundIssue(BaseModel):
    key: str = ""
    url: str = ""
    summary: str = ""
    type: str = ""
    original_estimate: int | None = None


@app.get('/jira/search')
def search(request: Request, q: str) -> List[FoundIssue]:
    if not request.session.get('access_token'):
        return []

    issues: List | dict[str, Any] | ResultList[Issue] = []
    q = q.strip()

    search = f'text ~ "{q}"'
    if '-' in q and q.index('-') == 2:
        search = f'key = "{q}"'
    if settings.limit_to_project:
        search += f' and project={settings.limit_to_project}'

    try:
        issues = jira.search_issues(search)
    except JIRAError as e:
        print(q, ' => ', e.text)
    
    return [
        FoundIssue(  # FIXME return type of `jira.search_issues` has some problem
            key=issue.key,  # type: ignore
            summary=issue.fields.summary,  # type: ignore
            url=issue.permalink(),  # type: ignore
            type=str(issue.fields.issuetype),  # type: ignore
            original_estimate=issue.fields.timeoriginalestimate,  # type: ignore
        )  # type: ignore
        for issue in issues
    ]
