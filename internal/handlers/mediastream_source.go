package handlers

import (
	"net/http"
	"net/url"
	"strings"

	"github.com/labstack/echo/v4"
)

// This opt-in Android WebView conversion API leaves the existing local-file
// REST requests and all ordinary web/Denshi playback semantics unchanged.
func (h *Handler) HandleRequestAndroidTVSourceTranscode(c echo.Context) error {
	if getClientPlatformFromContext(c) != ClientPlatformAndroidTV {
		return echo.NewHTTPError(http.StatusForbidden, "source conversion is available to Android TV clients")
	}
	if err := h.guardMediaConsumption(c); err != nil {
		return err
	}
	if err := h.guardPrivilegedMediastream(c, h.App.SecondarySettings.Mediastream); err != nil {
		return err
	}
	var body struct {
		SourceURL  string `json:"sourceUrl"`
		PlaybackID string `json:"playbackId"`
		ClientID   string `json:"clientId"`
	}
	if err := c.Bind(&body); err != nil {
		return h.RespondWithError(c, err)
	}
	clientID := getRequestClientId(c, body.ClientID)
	result, err := h.App.MediastreamRepository.RequestAndroidTVSourceTranscode(
		c.Request().Context(), strings.TrimSpace(body.SourceURL), body.PlaybackID, clientID,
		c.Scheme()+"://"+c.Request().Host, c.Request().Header)
	if err != nil {
		return h.RespondWithError(c, err)
	}
	if h.App.Config.Server.Password != "" {
		token, err := h.App.GetServerPasswordHMACAuth().GenerateToken("/api/v1/mediastream/source/" + result.SessionID + "/")
		if err != nil {
			h.App.MediastreamRepository.StopAndroidTVSourceTranscode(result.SessionID, clientID)
			return h.RespondWithError(c, err)
		}
		result.StreamURL += "?token=" + url.QueryEscape(token)
	}
	return h.RespondWithData(c, result)
}

func (h *Handler) HandleStopAndroidTVSourceTranscode(c echo.Context) error {
	if getClientPlatformFromContext(c) != ClientPlatformAndroidTV {
		return echo.NewHTTPError(http.StatusForbidden, "source conversion is available to Android TV clients")
	}
	if err := h.guardMediaConsumption(c); err != nil {
		return err
	}
	var body struct {
		SessionID string `json:"sessionId"`
		ClientID  string `json:"clientId"`
	}
	if err := c.Bind(&body); err != nil {
		return h.RespondWithError(c, err)
	}
	h.App.MediastreamRepository.StopAndroidTVSourceTranscode(body.SessionID, getRequestClientId(c, body.ClientID))
	return h.RespondWithData(c, true)
}

func (h *Handler) HandleAndroidTVSourceTranscode(c echo.Context) error {
	if err := h.guardMediaConsumption(c); err != nil {
		return err
	}
	return h.App.MediastreamRepository.ServeEchoAndroidTVSourceTranscode(c)
}
