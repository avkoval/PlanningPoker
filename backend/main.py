import asyncio
import copy
import json
import logging
import pprint
import re
from collections import defaultdict
from datetime import datetime
from typing import Any, List

import httpx
import starlette.status as status
from authlib.integrations.starlette_client import (  # type: ignore[import]
    OAuth, OAuthError)
from cachetools import TTLCache, cached
from fastapi import FastAPI, Request, WebSocket, WebSocketDisconnect
from fastapi.responses import FileResponse, HTMLResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from jira import JIRA
from jira.client import ResultList
from jira.exceptions import JIRAError
from jira.resources import Issue
from pydantic import BaseModel
from requests_oauthlib import OAuth2Session
from starlette.config import Config
from starlette.middleware.sessions import SessionMiddleware
from starlette.responses import RedirectResponse

from .config import Settings
from .websocket import notifier

settings = Settings()

# Configure logging
logging.basicConfig(
    level=getattr(logging, settings.log_level),
    format="%(asctime)s - %(name)s - %(levelname)s - %(message)s",
)
logger = logging.getLogger(__name__)
logger.debug(f"Logging initialized at level: {settings.log_level}")

# OAuth settings
GOOGLE_CLIENT_ID = settings.google_client_id
GOOGLE_CLIENT_SECRET = settings.google_client_secret
JIRA_CLIENT_ID = settings.jira_client_id
JIRA_CLIENT_SECRET = settings.jira_client_secret

if not all([GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET, JIRA_CLIENT_ID, JIRA_CLIENT_SECRET]):
    raise BaseException("Missing env variables")

# Ensure Jira Cloud ID is set for Jira API v3
if not settings.jira_cloud_id:
    logger.warning("JIRA_CLOUD_ID is not set. This is required for Jira OAuth API calls.")


if not all([GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET, JIRA_CLIENT_ID, JIRA_CLIENT_SECRET]):
    raise BaseException("Missing env variables")


# Set up oauth
config_data = {
    "GOOGLE_CLIENT_ID": GOOGLE_CLIENT_ID,
    "GOOGLE_CLIENT_SECRET": GOOGLE_CLIENT_SECRET,
}
starlette_config = Config(environ=config_data)
oauth = OAuth(starlette_config)
oauth.register(
    name="google",
    server_metadata_url="https://accounts.google.com/.well-known/openid-configuration",
    client_kwargs={"scope": "openid email profile"},
)

app = FastAPI()
app_data: dict = {
    "estimate-ticket": None,
    "votes": [],
    "users": [],
    "finished": False,
}


def get_estimate_ticket():
    global app_data
    return app_data["estimate-ticket"]


def is_finished():
    global app_data
    return app_data["finished"]


def store_reset(estimate_ticket: str | None) -> None:
    global app_data
    app_data["estimate-ticket"] = estimate_ticket
    app_data["votes"] = defaultdict(dict)
    app_data["users"] = []
    app_data["finished"] = False


class Vote(BaseModel):
    key: str = ""
    vote: str = ""
    category: str = ""
    stamp: datetime | None = None


def add_vote(username: str, vote: Vote):
    global app_data
    if app_data["estimate-ticket"] is None:
        store_reset(vote.key)
    if vote.key == app_data["estimate-ticket"]:
        votes = app_data["votes"][username]
        votes.update({vote.category: vote.vote})
        app_data["votes"][username] = votes
    else:
        print(f"Error: vote ticket {vote.key} is not the same as global estimate ticket {app_data['estimate-ticket']}")
    pprint.pprint(app_data)


SECRET_KEY = settings.secret_key
if SECRET_KEY is None:
    raise Exception("Missing SECRET_KEY")
app.add_middleware(SessionMiddleware, secret_key=SECRET_KEY)

app.mount("/app/", StaticFiles(directory="pp-front/public", html=True), name="app")
app.mount("/static/", StaticFiles(directory="static", html=True), name="static")
app.mount("/js/", StaticFiles(directory="pp-front/public/js/", html=True), name="static")

favicon_path = "static/favicon.ico"

jira = JIRA(
    options={"server": settings.jira_server},
    basic_auth=(settings.jira_user_email, settings.jira_token),
)

def get_jira_client_for_user(access_token=None):
    """
    Returns a JIRA client - either the default one or one authenticated with user's access token
    """
    if access_token:
        return JIRA(
            options={"server": "https://api.atlassian.com/ex/jira/" + settings.jira_cloud_id},
            token_auth=access_token
        )
    return jira


templates = Jinja2Templates(directory="templates")

def logged_in(request):
    if "access_token" in request.session:
        return True  # google oauth
    if "jira_access_token" in request.session:
        return True  # jira oauth
    return False

def is_jira_authenticated(request):
    """Check if the user is authenticated via Jira OAuth"""
    return "jira_access_token" in request.session


@app.get("/", response_class=HTMLResponse)
async def home(request: Request):
    notifications: List = []
    if logged_in(request):
        email = request.session["user_info"]["email"]
        domain = email.split("@")[1]
        allowed_domains = [d for d in settings.allowed_email_domains.split(",") if d != ""]
        if len(allowed_domains) and domain not in allowed_domains:
            msg = f"Domain not allowed: {domain} for user {email}"
            notifications.append({"type": "danger", "text": msg})
        else:
            return RedirectResponse("/app/")
    return templates.TemplateResponse(name="index.html", context={"request": request, "notifications": notifications})


@app.get("/favicon.ico", include_in_schema=False)
async def favicon():
    return FileResponse(favicon_path)


@app.route("/login-via-google")
async def login(request: Request):
    redirect_uri = request.url_for("auth")
    return await oauth.google.authorize_redirect(request, redirect_uri)


@app.post("/logout")
async def logout(request: Request):
    request.session.clear()
    return RedirectResponse("/", status_code=status.HTTP_302_FOUND)


class UserInfo(BaseModel):
    logged_in: bool = False
    email: str | None = None
    family_name: str | None = None
    given_name: str | None = None
    name: str | None = None
    picture: str | None = None
    auth_provider: str | None = None


async def get_jira_user_info(access_token):
    async with httpx.AsyncClient() as client:
        req = await client.get(
            "https://api.atlassian.com/me",
            headers={
                "Authorization": f"Bearer {access_token}",
                "Accept": "application/json",
            },
        )
        req.raise_for_status()
        response_data = req.json()
        logger.debug(f"Jira user info API response: {response_data}")
        return response_data


async def get_jira_cloud_id(access_token):
    """
    Retrieve the Jira Cloud ID using the access token.
    This can be used to set up the JIRA_CLOUD_ID environment variable.
    """
    async with httpx.AsyncClient() as client:
        req = await client.get(
            "https://api.atlassian.com/oauth/token/accessible-resources",
            headers={
                "Authorization": f"Bearer {access_token}",
                "Accept": "application/json",
            },
        )
        req.raise_for_status()
        resources = req.json()
        logger.info(f"Available Jira Cloud resources: {resources}")
        if resources and len(resources) > 0:
            # Return the ID of the first resource (usually there's only one)
            return resources[0]["id"]
        return None


@app.get("/userInfo")
async def user_info(request: Request) -> UserInfo:
    userinfo = request.session.get("user_info", {})
    auth_provider = ""
    if request.session.get("jira_access_token"):
        auth_provider = "Jira"
    elif request.session.get("access_token"):
        auth_provider = "Google"
    return UserInfo(
        logged_in=True,
        email=userinfo.get("email"),
        family_name=userinfo.get("family_name"),
        given_name=userinfo.get("given_name"),
        name=userinfo.get("name"),
        picture=userinfo.get("picture"),
        auth_provider=auth_provider
    )

@app.get("/jira-cloud-id")
async def jira_cloud_id(request: Request):
    """Get the Jira Cloud ID for the authenticated user"""
    if not is_jira_authenticated(request):
        return {"error": "Not authenticated with Jira"}

    access_token = request.session.get("jira_access_token")
    cloud_id = await get_jira_cloud_id(access_token)

    if cloud_id:
        return {"cloud_id": cloud_id, "message": "Add this to your .env file as JIRA_CLOUD_ID"}
    return {"error": "Could not retrieve Jira Cloud ID"}


@app.route("/auth")
async def auth(request: Request):
    try:
        data = await oauth.google.authorize_access_token(request)
        logger.debug(f"Google OAuth response: {data}")
    except OAuthError as e:
        logger.error(f"Google OAuth error: {e}")
        return templates.TemplateResponse(name="index.html", context={"request": request, "error": e})
    request.session.clear()
    request.session["access_token"] = data["access_token"]
    request.session["user_info"] = data["userinfo"]
    return RedirectResponse("/app/")


@app.route("/login-via-jira")
async def login_via_jira(request: Request):
    redirect_uri = request.url_for("auth_jira")
    scope = [
        "read:me",
        "read:jira-user",
        "read:jira-work",
        "read:project:jira",
        "write:jira-work",
        "write:issue:jira",
        "write:comment:jira",
        "redact:issue:jira",
    ]
    audience = "api.atlassian.com"
    authorization_base_url = "https://auth.atlassian.com/authorize"
    jira_oauth = OAuth2Session(JIRA_CLIENT_ID, scope=scope, redirect_uri=redirect_uri)
    authorization_url, state = jira_oauth.authorization_url(
        authorization_base_url,
        audience=audience,
    )
    request.session["jira_oauth_state"] = state
    return RedirectResponse(authorization_url, status_code=status.HTTP_302_FOUND)


@app.route("/auth-jira")
async def auth_jira(request: Request):
    token_url = "https://auth.atlassian.com/oauth/token"
    redirect_uri = request.url_for("auth_jira")

    code = request.query_params.get("code")
    state = request.query_params.get("state")
    logger.debug(f"Jira auth callback received - code: {code}, state: {state}")

    assert code and state
    assert state == request.session.pop("jira_oauth_state")

    jira_oauth = OAuth2Session(JIRA_CLIENT_ID, state=state, redirect_uri=redirect_uri)
    token_json = await asyncio.to_thread(
        jira_oauth.fetch_token, token_url, client_secret=JIRA_CLIENT_SECRET, code=code, state=state
    )
    logger.debug(f"Jira OAuth token response: {token_json}")

    request.session.clear()
    request.session["jira_access_token"] = token_json["access_token"]
    jira_user_info = await get_jira_user_info(token_json["access_token"])

    # Get and log the Jira Cloud ID
    cloud_id = await get_jira_cloud_id(token_json["access_token"])
    if cloud_id:
        logger.info(f"Jira Cloud ID: {cloud_id}")
        # You can set this in your .env file

    user_names = jira_user_info["name"].split()
    first_name = next(iter(user_names), "")
    last_name = user_names[1] if len(user_names) == 2 else ""
    request.session["user_info"] = dict(
        email=jira_user_info["email"],
        family_name=last_name,
        given_name=first_name,
        name=jira_user_info["nickname"],
        picture=jira_user_info["picture"],
    )
    return RedirectResponse("/app/")


class FoundIssue(BaseModel):
    key: str = ""
    url: str = ""
    summary: str = ""
    type: str = ""
    original_estimate: int | None = None


@cached(cache=TTLCache(maxsize=1024, ttl=600))
def search_jira_issues(search):
    try:
        results = jira.search_issues(search)
        logger.debug(f"JIRA search API response for '{search}': {results}")
        return results
    except JIRAError as e:
        logger.error(f"JIRA search error for '{search}': {e.text}")
    return []


@app.get("/jira/search")
def search(request: Request, q: str) -> List[FoundIssue]:
    if not logged_in(request):
        logger.warn(f"Not logged in, returning an empty array for request {q}")
        return []
    # user = username(request.session.get('access_token')['userinfo'])  # type: ignore

    issues: List | dict[str, Any] | ResultList[Issue] = []
    q = q.strip()
    if q.startswith("jql:"):
        search = q[4:]
    else:
        search = f'text ~ "{q}"'
        if "-" in q and q.index("-") == 2:
            search = f'key = "{q}"'
        if settings.limit_to_project:
            search += f" and project={settings.limit_to_project}"

    issues = search_jira_issues(search)
    logger.debug("Search Results: %s", issues)
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


class IssueDetail(BaseModel):
    key: str = ""
    url: str = ""
    summary: str = ""
    description: str = ""
    updated: str = ""
    created: str = ""
    issuetype: str = ""
    priority: str = ""
    reporter: str = ""
    assignee: str = ""
    aggregatetimespent: int = 0
    comments: List[str] = []


@cached(cache=TTLCache(maxsize=1024, ttl=600))
def get_issue_info(issue_key: str):
    issue_key = issue_key.strip()
    try:
        issue = jira.issue(issue_key, expand="renderedFields")
        logger.debug(f"JIRA issue API response for '{issue_key}': {issue}")
        return issue
    except JIRAError as e:
        logger.error(f"JIRA issue error for '{issue_key}': {e.text}")
        return None


def remove_img_tags(data):
    p = re.compile(r"<img.*?/>")
    return p.sub("", data)


@app.get("/jira/info")
def detail(request: Request, issue_key: str) -> IssueDetail | None:
    if not logged_in(request):
        return None
    issue = get_issue_info(issue_key)
    user = get_username(request.session.get("user_info"))  # type: ignore
    now = format_datetime(datetime.now())
    if get_estimate_ticket() != issue.key:
        log_msg = f"{now} Voting started by {user} for Jira ticket № {issue.key}"
        logger.info(log_msg)
        asyncio.run(push_to_connected_websockets(f"start voting:: {issue.key}"))
        asyncio.run(push_to_connected_websockets(f"log:: {log_msg}"))
        store_reset(issue.key)

    detail = IssueDetail(
        key=issue.key,
        url=issue.permalink(),
        summary=issue.fields.summary,
        description=remove_img_tags(issue.raw["renderedFields"]["description"]),
        updated=issue.fields.updated,
        created=issue.fields.created,
        issuetype=str(issue.fields.issuetype),
        priority=str(issue.fields.priority),
        reporter=str(issue.fields.reporter),
        assignee=str(issue.fields.assignee),
        aggregatetimespent=issue.fields.aggregatetimespent or 0,
    )
    return detail


def get_username(userinfo: dict) -> str:
    return f"{userinfo['given_name']} {userinfo['family_name']} {userinfo['email']}"


def format_datetime(dt):
    return dt.strftime("%Y/%m/%d, %H:%M:%S")


@app.post("/vote")
def vote(request: Request, vote: Vote) -> Vote | None:
    if not logged_in(request):
        return None
    vote.stamp = datetime.now()
    username = get_username(request.session.get("user_info"))  # type: ignore
    if is_finished():
        votes = copy.deepcopy(app_data["votes"])
        msg = f"Invalid vote attempt from {username} as voting process is already finished"
        logger.warning(msg)
        asyncio.run(push_to_connected_websockets(f"log::{format_datetime(vote.stamp)} {msg}"))
        return None
    else:
        add_vote(username, vote)  # type: ignore
        votes = copy.deepcopy(app_data["votes"])

    asyncio.run(push_to_connected_websockets(f"log::{format_datetime(vote.stamp)} Got Vote from {username}"))
    for v in votes:
        for category in votes[v]:
            votes[v][category] = "✓"
    logger.debug(f"Current votes: {votes}")
    asyncio.run(push_to_connected_websockets("results::" + json.dumps(votes)))
    return vote


class JiraEstimateComment(BaseModel):
    key: str = ""
    text: str = ""


def find_matching_comment(comments):
    """
    try to find comment, containing forms:
      Backend - .*
      QA - .*
      Back:
      Front:
      ... and qualify this comment as estimate, otherwise return None
    """
    for comment in comments:
        for pattern in [
            r"Backend\s\-.*",
            r"QA\s\-.*",
            r"Front\s\-.*",
            r"^Back:.*",
            r"^Front:.*",
        ]:
            if re.match(pattern, comment.body):
                return comment


@app.post("/add-estimate-comment")
def add_estimate_comment(request: Request, comment: JiraEstimateComment) -> JiraEstimateComment | None:
    if not logged_in(request):
        return None

    # Only allow Jira authenticated users to modify tickets
    if not is_jira_authenticated(request):
        logger.warning("Attempt to modify Jira ticket without Jira authentication")
        return None

    username = get_username(request.session.get("user_info"))  # type: ignore
    jira_access_token = request.session.get("jira_access_token")

    # Use the user's Jira access token for API calls
    user_jira = get_jira_client_for_user(jira_access_token)

    issue = user_jira.issue(comment.key)
    logger.debug(f"JIRA issue API response for comment on '{comment.key}': {issue}")

    estimate_comment = find_matching_comment(issue.fields.comment.comments)
    if estimate_comment:
        response = estimate_comment.update(body=comment.text)
        logger.debug(f"JIRA update comment response: {response}")
    else:
        response = user_jira.add_comment(issue, comment.text)
        logger.debug(f"JIRA add comment response: {response}")

    asyncio.run(
        push_to_connected_websockets(
            f"log::{format_datetime(datetime.now())} Saved comment of {username} to ticket {comment.key}"
        )
    )
    return comment


@app.post("/vote/finish")
def vote_finish(request: Request) -> None:
    global app_data
    if logged_in(request):
        app_data["finished"] = True
        asyncio.run(push_to_connected_websockets("results::" + json.dumps(app_data["votes"])))


@app.websocket("/ws")
async def websocket_endpoint(websocket: WebSocket):
    global app_data
    await notifier.connect(websocket)
    try:
        while True:
            data = await websocket.receive_text()
            logger.debug(f"WebSocket received: {data}")
            match data:
                case "sync":
                    current_ticket = get_estimate_ticket()
                    logger.debug("Syncing new websocket client")
                    if current_ticket:
                        logger.debug(f"Current ticket is: {current_ticket}")
                        await websocket.send_text(f"start voting:: {current_ticket}")
                        if is_finished():
                            await websocket.send_text("results::" + json.dumps(app_data["votes"]))
    except WebSocketDisconnect:
        notifier.remove(websocket)
        logger.debug("WebSocketDisconnect detected")


@app.on_event("startup")
async def startup():
    await notifier.generator.asend(None)


async def push_to_connected_websockets(message: str):
    await notifier.push(message)
