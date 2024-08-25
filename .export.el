(setq org-safe-remote-resources '("https://raw.githubusercontent.com/fniessen/org-html-themes/master/org/theme-readtheorg.setup"))
(find-file "README.org")
(org-html-export-to-html)
