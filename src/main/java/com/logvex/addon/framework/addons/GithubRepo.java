package com.logvex.addon.framework.addons;

/** A GitHub repository reference, used for update checks and the about screen. */
public record GithubRepo(String owner, String name) {
    public String url() {
        return "https://github.com/" + owner + "/" + name;
    }
}
